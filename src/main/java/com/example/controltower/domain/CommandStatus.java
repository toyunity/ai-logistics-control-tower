package com.example.controltower.domain;

public enum CommandStatus {
    PENDING,
    EXECUTING,
    SUCCEEDED,
    FAILED,
    UNCONFIRMED,
    REJECTED,
    SUPERSEDED,
    EXPIRED
}
