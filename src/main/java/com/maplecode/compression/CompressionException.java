package com.maplecode.compression;

public class CompressionException extends RuntimeException {
    public CompressionException(String message) { super(message); }
    public CompressionException(String message, Throwable cause) { super(message, cause); }
}
