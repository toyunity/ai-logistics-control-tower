package com.example.controltower.domain;

public record Actor(String accountId, Role role) {
    public static final Actor ANONYMOUS = new Actor(null, Role.VIEWER);
}
