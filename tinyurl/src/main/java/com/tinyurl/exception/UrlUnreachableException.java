package com.tinyurl.exception;

public class UrlUnreachableException extends RuntimeException {
    public UrlUnreachableException(String message) {
        super(message);
    }
}
