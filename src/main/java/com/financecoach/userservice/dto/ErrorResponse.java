// src/main/java/com/financecoach/userservice/dto/ErrorResponse.java
package com.financecoach.userservice.dto;

public class ErrorResponse {
    private String message;

    public ErrorResponse() {}

    public ErrorResponse(String message) {
        this.message = message;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}