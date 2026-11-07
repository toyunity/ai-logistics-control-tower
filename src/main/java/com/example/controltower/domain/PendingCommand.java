package com.example.controltower.domain;

import static java.util.Objects.requireNonNull;

import java.time.Instant;
import java.util.UUID;

public record PendingCommand(
        UUID id,
        String accountId,
        CommandSpec spec,
        String stateDigest,
        String impact,
        CommandStatus status,
        Instant createdAt,
        Instant expiresAt) {

    public PendingCommand {
        requireNonNull(id);
        requireNonNull(accountId);
        requireNonNull(spec);
        requireNonNull(stateDigest);
        requireNonNull(impact);
        requireNonNull(status);
        requireNonNull(createdAt);
        requireNonNull(expiresAt);
    }
}
