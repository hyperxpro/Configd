package io.configd.common.config;

/**
 * Thrown when configuration cannot be resolved to a usable value: a required key is absent or blank,
 * or a present value cannot be parsed to the requested type (int/long/boolean), or a config file is
 * malformed. Configuration is loaded once at boot and is fail-closed - an unusable value is a startup
 * error, never a silent fallback to a default, because a config store that silently misreads its own
 * configuration is how a security or durability posture quietly becomes fiction.
 */
public final class ConfigException extends RuntimeException {

    public ConfigException(String message) {
        super(message);
    }

    public ConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
