package com.apg.provider.api;

/** Provider could not be reached or its answer could not be trusted/understood: the money outcome is UNKNOWN. */
public class ProviderException extends RuntimeException {
    private final String code;

    public ProviderException(String code, String message) {
        super(message);
        this.code = code;
    }

    public ProviderException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() { return code; }
}
