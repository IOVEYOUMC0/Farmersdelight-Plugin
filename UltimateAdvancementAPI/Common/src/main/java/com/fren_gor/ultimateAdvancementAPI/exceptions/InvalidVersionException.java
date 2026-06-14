package com.fren_gor.ultimateAdvancementAPI.exceptions;

import org.jetbrains.annotations.Nullable;

/**
 * This exception is thrown when an invalid version is detected.
 */
public class InvalidVersionException extends RuntimeException {

    @Nullable
    private String expected, found;

    public InvalidVersionException() {
        super();
    }

    public InvalidVersionException(String message) {
        super(message);
    }

    public InvalidVersionException(String message, Throwable cause) {
        super(message, cause);
    }

    public InvalidVersionException(Throwable cause) {
        super(cause);
    }

    protected InvalidVersionException(String message, Throwable cause, boolean enableSuppression, boolean writableStackTrace) {
        super(message, cause, enableSuppression, writableStackTrace);
    }

    /**
     * Creates a new InvalidVersionException.
     *
     * @param expected The expected version.
     * @param found The real version.
     */
    public InvalidVersionException(@Nullable String expected, @Nullable String found) {
        super();
        this.expected = expected;
        this.found = found;
    }

    /**
     * Creates a new InvalidVersionException.
     *
     * @param expected The expected version.
     * @param found The real version.
     * @param message See RuntimeException#RuntimeException(String).
     */
    public InvalidVersionException(@Nullable String expected, @Nullable String found, String message) {
        super(message);
        this.expected = expected;
        this.found = found;
    }

    /**
     * Creates a new InvalidVersionException.
     *
     * @param expected The expected version.
     * @param found The real version.
     * @param message See RuntimeException#RuntimeException(String, Throwable).
     * @param cause See RuntimeException#RuntimeException(String, Throwable).
     */
    public InvalidVersionException(@Nullable String expected, @Nullable String found, String message, Throwable cause) {
        super(message, cause);
        this.expected = expected;
        this.found = found;
    }

    /**
     * Creates a new InvalidVersionException.
     *
     * @param expected The expected version.
     * @param found The real version.
     * @param cause See RuntimeException#RuntimeException(Throwable).
     */
    public InvalidVersionException(@Nullable String expected, @Nullable String found, Throwable cause) {
        super(cause);
        this.expected = expected;
        this.found = found;
    }

    /**
     * Creates a new InvalidVersionException.
     *
     * @param expected The expected version.
     * @param found The real version.
     * @param message See RuntimeException#RuntimeException(String, Throwable, boolean, boolean).
     * @param cause See RuntimeException#RuntimeException(String, Throwable, boolean, boolean).
     * @param enableSuppression See RuntimeException#RuntimeException(String, Throwable, boolean, boolean).
     * @param writableStackTrace See RuntimeException#RuntimeException(String, Throwable, boolean, boolean).
     */
    protected InvalidVersionException(@Nullable String expected, @Nullable String found, String message, Throwable cause, boolean enableSuppression, boolean writableStackTrace) {
        super(message, cause, enableSuppression, writableStackTrace);
        this.expected = expected;
        this.found = found;
    }

    /**
     * Gets the expected version.
     *
     * @return The expected version. Might be null.
     */
    @Nullable
    public String getExpected() {
        return expected;
    }

    /**
     * Gets the real version.
     *
     * @return The real version. Might be null.
     */
    @Nullable
    public String getFound() {
        return found;
    }
}
