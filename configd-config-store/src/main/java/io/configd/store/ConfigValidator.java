package io.configd.store;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * Pluggable validation for config values before they are written.
 * <p>
 * Validators are registered per key prefix. When validating a key, the
 * validator with the longest matching prefix is selected. If no prefix
 * matches, the value is considered valid.
 * <p>
 * <b>Thread safety:</b> Registration and validation may be called from
 * any thread concurrently. The prefix map uses a {@link ConcurrentSkipListMap}
 * for lock-free reads and correct prefix ordering.
 *
 * @see ValidationResult
 * @see Validator
 */
public final class ConfigValidator {

    /**
     * Prefix-to-validator mapping. ConcurrentSkipListMap provides ordered
     * iteration for longest-prefix matching and lock-free reads.
     */
    private final ConcurrentNavigableMap<String, Validator> prefixValidators =
            new ConcurrentSkipListMap<>();

    // -----------------------------------------------------------------------
    // Registration
    // -----------------------------------------------------------------------

    /**
     * Registers a validator for the given key prefix.
     * <p>
     * If a validator is already registered for this exact prefix, it is
     * replaced. Only the longest matching prefix validator is invoked
     * during validation.
     *
     * @param prefix    the key prefix to match (non-null, non-empty)
     * @param validator the validator to invoke for matching keys (non-null)
     * @return this instance for fluent registration
     */
    public ConfigValidator register(String prefix, Validator validator) {
        Objects.requireNonNull(prefix, "prefix must not be null");
        if (prefix.isEmpty()) {
            throw new IllegalArgumentException("prefix must not be empty");
        }
        Objects.requireNonNull(validator, "validator must not be null");
        prefixValidators.put(prefix, validator);
        return this;
    }

    /**
     * Removes the validator for the given prefix.
     *
     * @param prefix the prefix to deregister
     * @return true if a validator was found and removed
     */
    public boolean deregister(String prefix) {
        return prefixValidators.remove(prefix) != null;
    }

    // -----------------------------------------------------------------------
    // Validation
    // -----------------------------------------------------------------------

    /**
     * Validates a config key-value pair against the registered validators.
     * <p>
     * The validator with the longest matching prefix is selected. If no
     * prefix matches, the value is considered {@link ValidationResult.Valid}.
     *
     * @param key   config key (non-null)
     * @param value raw config bytes (non-null)
     * @return the validation result
     */
    public ValidationResult validate(String key, byte[] value) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(value, "value must not be null");

        Validator validator = findLongestPrefixValidator(key);
        if (validator == null) {
            return ValidationResult.Valid.INSTANCE;
        }
        return validator.validate(key, value);
    }

    /**
     * Validates a list of mutations, returning the first validation failure
     * or {@link ValidationResult.Valid} if all pass.
     *
     * @param mutations the mutations to validate (non-null)
     * @return the first invalid result, or valid if all pass
     */
    public ValidationResult validateAll(java.util.List<ConfigMutation> mutations) {
        Objects.requireNonNull(mutations, "mutations must not be null");
        for (ConfigMutation mutation : mutations) {
            if (mutation instanceof ConfigMutation.Put put) {
                ValidationResult result = validate(put.key(), put.valueUnsafe());
                if (result instanceof ValidationResult.Invalid) {
                    return result;
                }
            }
            // Deletes are always valid
        }
        return ValidationResult.Valid.INSTANCE;
    }

    // -----------------------------------------------------------------------
    // Internal
    // -----------------------------------------------------------------------

    /**
     * Finds the validator with the longest prefix that matches the given key.
     * Uses descending iteration from the key itself to find the best match.
     */
    private Validator findLongestPrefixValidator(String key) {
        // Iterate from the key downward to find the longest matching prefix
        Map.Entry<String, Validator> entry = prefixValidators.floorEntry(key);
        while (entry != null) {
            if (key.startsWith(entry.getKey())) {
                return entry.getValue();
            }
            // Try the next lower entry
            entry = prefixValidators.lowerEntry(entry.getKey());
        }
        return null;
    }

    // -----------------------------------------------------------------------
    // Validator functional interface
    // -----------------------------------------------------------------------

    /**
     * A validation function for config values.
     * <p>
     * Implementations should be stateless and thread-safe. They are invoked
     * on the caller's thread.
     */
    @FunctionalInterface
    public interface Validator {

        /**
         * Validates a config key-value pair.
         *
         * @param key   the config key
         * @param value the raw config bytes
         * @return the validation result
         */
        ValidationResult validate(String key, byte[] value);
    }

    // -----------------------------------------------------------------------
    // ValidationResult sealed hierarchy
    // -----------------------------------------------------------------------

    /**
     * The result of validating a config value. Sealed to the two permitted
     * variants: {@link Valid} and {@link Invalid}.
     */
    public sealed interface ValidationResult permits ValidationResult.Valid, ValidationResult.Invalid {

        /** Returns true if validation passed. */
        default boolean isValid() {
            return this instanceof Valid;
        }

        /**
         * Validation passed.
         */
        record Valid() implements ValidationResult {
            /** Singleton instance. */
            static final Valid INSTANCE = new Valid();
        }

        /**
         * Validation failed with a reason.
         *
         * @param reason human-readable explanation of the validation failure
         */
        record Invalid(String reason) implements ValidationResult {
            public Invalid {
                Objects.requireNonNull(reason, "reason must not be null");
            }
        }
    }
}
