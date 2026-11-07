package com.example.controltower.application.port.in;

import com.example.controltower.domain.CommandStatus;

/** 메시지는 서버가 정한 고정 문구다. NOT_FOUND와 FORBIDDEN이면 status는 null이다. 남의 카드 상태를 알리지 않으려는 것이다. */
public record CommandResult(Outcome outcome, CommandStatus status, String message) {

    public enum Outcome {
        OK,
        FORBIDDEN,
        NOT_FOUND,
        CONFLICT
    }
}
