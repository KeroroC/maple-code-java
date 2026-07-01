package com.maplecode.error;

public class MapleCodeException extends RuntimeException {
    public MapleCodeException(String message) {
        super(message);
    }
    public MapleCodeException(String message, Throwable cause) {
        super(message, cause);
    }
}
