package com.tinyurl.controller;

import com.tinyurl.dto.ErrorResponse;
import com.tinyurl.exception.GoneException;
import com.tinyurl.exception.NotFoundException;
import jakarta.validation.ConstraintViolationException;
import jakarta.persistence.PersistenceException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String code = "INVALID_REQUEST";
        FieldError fieldError = ex.getBindingResult().getFieldError();
        if (fieldError != null && fieldError.getDefaultMessage() != null) {
            code = fieldError.getDefaultMessage();
        }
        return ResponseEntity.badRequest().body(new ErrorResponse(code, messageForCode(code)));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException ex) {
        return ResponseEntity.badRequest()
            .body(new ErrorResponse("INVALID_URL", messageForCode("INVALID_URL")));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        String code = ex.getMessage() == null ? "INVALID_REQUEST" : ex.getMessage();
        HttpStatus status = "INVALID_EXPIRY".equals(code) || "INVALID_URL".equals(code)
            ? HttpStatus.BAD_REQUEST
            : HttpStatus.INTERNAL_SERVER_ERROR;
        return ResponseEntity.status(status).body(new ErrorResponse(code, messageForCode(code)));
    }

    @ExceptionHandler({DataAccessException.class, PersistenceException.class})
    public ResponseEntity<ErrorResponse> handleServiceUnavailable(Exception ex) {
        HttpHeaders headers = new HttpHeaders();
        headers.add("Retry-After", "30");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .headers(headers)
            .body(new ErrorResponse("SERVICE_UNAVAILABLE", "The service is temporarily unavailable. Please try again."));
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(NotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(new ErrorResponse("NOT_FOUND", ex.getMessage()));
    }

    @ExceptionHandler(GoneException.class)
    public ResponseEntity<ErrorResponse> handleGone(GoneException ex) {
        return ResponseEntity.status(HttpStatus.GONE)
            .body(new ErrorResponse("GONE", ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(new ErrorResponse("INTERNAL_ERROR", "An unexpected error occurred. Please try again."));
    }

    private String messageForCode(String code) {
        return switch (code) {
            case "INVALID_URL" -> "URL must be a valid HTTP or HTTPS address (max 2048 characters).";
            case "INVALID_EXPIRY" -> "Expiry must be a positive integer not greater than 3650 days.";
            default -> "Request validation failed.";
        };
    }
}
