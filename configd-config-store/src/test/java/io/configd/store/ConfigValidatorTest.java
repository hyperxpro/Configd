package io.configd.store;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ConfigValidator}.
 */
class ConfigValidatorTest {

    private ConfigValidator validator;

    @BeforeEach
    void setUp() {
        validator = new ConfigValidator();
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    // -----------------------------------------------------------------------
    // No validators registered
    // -----------------------------------------------------------------------

    @Nested
    class NoValidatorsRegistered {

        @Test
        void anyKeyIsValidWhenNoValidatorsRegistered() {
            var result = validator.validate("any.key", bytes("value"));
            assertTrue(result.isValid());
            assertInstanceOf(ConfigValidator.ValidationResult.Valid.class, result);
        }
    }

    // -----------------------------------------------------------------------
    // Prefix matching
    // -----------------------------------------------------------------------

    @Nested
    class PrefixMatching {

        @Test
        void exactPrefixMatch() {
            validator.register("db.", (key, value) ->
                    new ConfigValidator.ValidationResult.Invalid("db keys rejected"));

            var result = validator.validate("db.host", bytes("localhost"));
            assertFalse(result.isValid());
            assertInstanceOf(ConfigValidator.ValidationResult.Invalid.class, result);

            ConfigValidator.ValidationResult.Invalid invalid =
                    (ConfigValidator.ValidationResult.Invalid) result;
            assertEquals("db keys rejected", invalid.reason());
        }

        @Test
        void prefixDoesNotMatchOtherKeys() {
            validator.register("db.", (key, value) ->
                    new ConfigValidator.ValidationResult.Invalid("rejected"));

            var result = validator.validate("cache.ttl", bytes("300"));
            assertTrue(result.isValid());
        }

        @Test
        void longestPrefixWins() {
            validator.register("db.", (key, value) ->
                    new ConfigValidator.ValidationResult.Invalid("short prefix"));
            validator.register("db.conn.", (key, value) ->
                    ConfigValidator.ValidationResult.Valid.INSTANCE);

            // "db.conn.pool" matches "db.conn." (longest), which is valid
            var result = validator.validate("db.conn.pool", bytes("10"));
            assertTrue(result.isValid());

            // "db.host" matches "db." (only matching prefix), which is invalid
            var result2 = validator.validate("db.host", bytes("localhost"));
            assertFalse(result2.isValid());
        }

        @Test
        void singleCharPrefix() {
            validator.register("x", (key, value) ->
                    new ConfigValidator.ValidationResult.Invalid("x keys rejected"));

            var result = validator.validate("xyz", bytes("value"));
            assertFalse(result.isValid());
        }
    }

    // -----------------------------------------------------------------------
    // Validator logic
    // -----------------------------------------------------------------------

    @Nested
    class ValidatorLogic {

        @Test
        void validatorReceivesCorrectKeyAndValue() {
            String[] capturedKey = new String[1];
            byte[][] capturedValue = new byte[1][];

            validator.register("app.", (key, value) -> {
                capturedKey[0] = key;
                capturedValue[0] = value;
                return ConfigValidator.ValidationResult.Valid.INSTANCE;
            });

            validator.validate("app.setting", bytes("myvalue"));

            assertEquals("app.setting", capturedKey[0]);
            assertArrayEquals(bytes("myvalue"), capturedValue[0]);
        }

        @Test
        void validatorCanRejectBasedOnValueContent() {
            validator.register("port.", (key, value) -> {
                String v = new String(value, StandardCharsets.UTF_8);
                try {
                    int port = Integer.parseInt(v);
                    if (port < 1 || port > 65535) {
                        return new ConfigValidator.ValidationResult.Invalid(
                                "Port must be between 1 and 65535");
                    }
                    return ConfigValidator.ValidationResult.Valid.INSTANCE;
                } catch (NumberFormatException e) {
                    return new ConfigValidator.ValidationResult.Invalid(
                            "Port must be a number");
                }
            });

            assertTrue(validator.validate("port.http", bytes("8080")).isValid());
            assertFalse(validator.validate("port.http", bytes("99999")).isValid());
            assertFalse(validator.validate("port.http", bytes("not-a-number")).isValid());
        }
    }

    // -----------------------------------------------------------------------
    // Registration management
    // -----------------------------------------------------------------------

    @Nested
    class RegistrationManagement {

        @Test
        void registerReturnsSelfForFluency() {
            ConfigValidator result = validator.register("prefix.", (k, v) ->
                    ConfigValidator.ValidationResult.Valid.INSTANCE);
            assertSame(validator, result);
        }

        @Test
        void registerOverwritesPreviousValidator() {
            validator.register("db.", (key, value) ->
                    ConfigValidator.ValidationResult.Valid.INSTANCE);
            validator.register("db.", (key, value) ->
                    new ConfigValidator.ValidationResult.Invalid("replaced"));

            var result = validator.validate("db.host", bytes("localhost"));
            assertFalse(result.isValid());
        }

        @Test
        void deregisterRemovesValidator() {
            validator.register("db.", (key, value) ->
                    new ConfigValidator.ValidationResult.Invalid("rejected"));

            assertTrue(validator.deregister("db."));

            var result = validator.validate("db.host", bytes("localhost"));
            assertTrue(result.isValid());
        }

        @Test
        void deregisterReturnsFalseForAbsentPrefix() {
            assertFalse(validator.deregister("nonexistent."));
        }

        @Test
        void registerNullPrefixThrows() {
            assertThrows(NullPointerException.class,
                    () -> validator.register(null, (k, v) ->
                            ConfigValidator.ValidationResult.Valid.INSTANCE));
        }

        @Test
        void registerEmptyPrefixThrows() {
            assertThrows(IllegalArgumentException.class,
                    () -> validator.register("", (k, v) ->
                            ConfigValidator.ValidationResult.Valid.INSTANCE));
        }

        @Test
        void registerNullValidatorThrows() {
            assertThrows(NullPointerException.class,
                    () -> validator.register("prefix.", null));
        }
    }

    // -----------------------------------------------------------------------
    // Validate null checks
    // -----------------------------------------------------------------------

    @Nested
    class NullChecks {

        @Test
        void validateNullKeyThrows() {
            assertThrows(NullPointerException.class,
                    () -> validator.validate(null, bytes("value")));
        }

        @Test
        void validateNullValueThrows() {
            assertThrows(NullPointerException.class,
                    () -> validator.validate("key", null));
        }
    }

    // -----------------------------------------------------------------------
    // Batch validation (validateAll)
    // -----------------------------------------------------------------------

    @Nested
    class BatchValidation {

        @Test
        void validateAllReturnsValidWhenAllPass() {
            validator.register("db.", (key, value) ->
                    ConfigValidator.ValidationResult.Valid.INSTANCE);

            List<ConfigMutation> mutations = List.of(
                    new ConfigMutation.Put("db.host", bytes("localhost")),
                    new ConfigMutation.Put("db.port", bytes("5432")),
                    new ConfigMutation.Delete("db.old") // deletes always valid
            );

            assertTrue(validator.validateAll(mutations).isValid());
        }

        @Test
        void validateAllReturnsFirstFailure() {
            validator.register("db.", (key, value) -> {
                if (new String(value, StandardCharsets.UTF_8).isEmpty()) {
                    return new ConfigValidator.ValidationResult.Invalid(
                            "empty value for " + key);
                }
                return ConfigValidator.ValidationResult.Valid.INSTANCE;
            });

            List<ConfigMutation> mutations = List.of(
                    new ConfigMutation.Put("db.host", bytes("localhost")),
                    new ConfigMutation.Put("db.port", new byte[0]), // invalid
                    new ConfigMutation.Put("db.name", bytes("mydb"))
            );

            var result = validator.validateAll(mutations);
            assertFalse(result.isValid());
            ConfigValidator.ValidationResult.Invalid invalid =
                    (ConfigValidator.ValidationResult.Invalid) result;
            assertEquals("empty value for db.port", invalid.reason());
        }

        @Test
        void validateAllSkipsDeletes() {
            validator.register("db.", (key, value) ->
                    new ConfigValidator.ValidationResult.Invalid("all rejected"));

            List<ConfigMutation> mutations = List.of(
                    new ConfigMutation.Delete("db.old")
            );

            // Deletes skip validation, so this should pass
            assertTrue(validator.validateAll(mutations).isValid());
        }

        @Test
        void validateAllEmptyListIsValid() {
            assertTrue(validator.validateAll(List.of()).isValid());
        }

        @Test
        void validateAllNullThrows() {
            assertThrows(NullPointerException.class,
                    () -> validator.validateAll(null));
        }
    }

    // -----------------------------------------------------------------------
    // ValidationResult sealed interface
    // -----------------------------------------------------------------------

    @Nested
    class ValidationResultTests {

        @Test
        void validIsValid() {
            assertTrue(ConfigValidator.ValidationResult.Valid.INSTANCE.isValid());
        }

        @Test
        void invalidIsNotValid() {
            var invalid = new ConfigValidator.ValidationResult.Invalid("reason");
            assertFalse(invalid.isValid());
            assertEquals("reason", invalid.reason());
        }

        @Test
        void invalidNullReasonThrows() {
            assertThrows(NullPointerException.class,
                    () -> new ConfigValidator.ValidationResult.Invalid(null));
        }
    }
}
