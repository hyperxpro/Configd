package io.configd.store;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * Thread-safe prefix-based validation via ConcurrentSkipListMap for lock-free reads.
 *
 * @see ValidationResult
 * @see Validator
 */
public final class ConfigValidator {

    private final ConcurrentNavigableMap<String, Validator> prefixValidators =
            new ConcurrentSkipListMap<>();

    /**
     * Longest matching prefix wins during validation.
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

    public boolean deregister(String prefix) {
        return prefixValidators.remove(prefix) != null;
    }

    public ValidationResult validate(String key, byte[] value) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(value, "value must not be null");

        Validator validator = findLongestPrefixValidator(key);
        if (validator == null) {
            return ValidationResult.Valid.INSTANCE;
        }
        return validator.validate(key, value);
    }

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

    private Validator findLongestPrefixValidator(String key) {
        Map.Entry<String, Validator> entry = prefixValidators.floorEntry(key);
        while (entry != null) {
            if (key.startsWith(entry.getKey())) {
                return entry.getValue();
            }
            entry = prefixValidators.lowerEntry(entry.getKey());
        }
        return null;
    }

    @FunctionalInterface
    public interface Validator {
        ValidationResult validate(String key, byte[] value);
    }

    public sealed interface ValidationResult permits ValidationResult.Valid, ValidationResult.Invalid {
        default boolean isValid() {
            return this instanceof Valid;
        }

        record Valid() implements ValidationResult {
            static final Valid INSTANCE = new Valid();
        }

        record Invalid(String reason) implements ValidationResult {
            public Invalid {
                Objects.requireNonNull(reason, "reason must not be null");
            }
        }
    }
}
