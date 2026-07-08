package com.maplecode.compact;

public class CompactException extends RuntimeException {
    public CompactException(String message) { super(message); }
    public CompactException(String message, Throwable cause) { super(message, cause); }
}
