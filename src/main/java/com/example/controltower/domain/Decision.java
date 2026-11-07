package com.example.controltower.domain;

public sealed interface Decision {

    record Allowed() implements Decision {}

    record Denied(DenialCode code) implements Decision {}
}
