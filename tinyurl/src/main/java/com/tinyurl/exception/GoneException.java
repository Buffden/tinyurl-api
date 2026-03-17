package com.tinyurl.exception;

public class GoneException extends RuntimeException {
    public GoneException(String message) {
        super(message);
    }
}
