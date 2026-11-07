package com.example.controltower.domain;

public class GatewayUnavailableException extends RuntimeException {

    public GatewayUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
