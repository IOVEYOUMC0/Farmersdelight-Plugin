package com.huidu.farmersdelight.api.config;

/**
 * Base runtime exception for configuration read failures. Concrete, failure-specific subclasses let
 * callers distinguish "the key is absent" from "the value cannot be coerced to the requested type"
 * instead of treating both as null / a generic error.
 */
public abstract class ConfigParsingException extends RuntimeException {

    private final String path;

    protected ConfigParsingException(String path, String message) {
        super(message);
        this.path = path;
    }

    protected ConfigParsingException(String path, String message, Throwable cause) {
        super(message, cause);
        this.path = path;
    }

    public String path() {
        return path;
    }
}