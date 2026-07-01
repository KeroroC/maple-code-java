package com.maplecode.error;

public class ProviderException extends MapleCodeException {
    public ProviderException(String message) {
        super(message);
    }
    public ProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
