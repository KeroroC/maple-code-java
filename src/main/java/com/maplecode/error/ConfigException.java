package com.maplecode.error;

public class ConfigException extends MapleCodeException {
    public ConfigException(String message) {
        super(message);
    }
    public ConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
