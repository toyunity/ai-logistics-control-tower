package com.example.controltower.domain;

/** 전송이 타임아웃되어 명령이 도달했는지 알 수 없다. 재시도하면 안 된다. */
public class CommandOutcomeUnknownException extends GatewayUnavailableException {

    public CommandOutcomeUnknownException(String message, Throwable cause) {
        super(message, cause);
    }
}
