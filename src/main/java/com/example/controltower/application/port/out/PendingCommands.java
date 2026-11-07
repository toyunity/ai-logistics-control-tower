package com.example.controltower.application.port.out;

import com.example.controltower.domain.CommandSpec;
import com.example.controltower.domain.CommandStatus;
import com.example.controltower.domain.PendingCommand;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface PendingCommands {

    void save(PendingCommand command);

    Optional<PendingCommand> find(UUID id);

    Optional<PendingCommand> findLive(String accountId, CommandSpec.Kind kind, String targetId, Instant now);

    /** from이 PENDING이면 expires_at > now 조건도 함께 건다. */
    boolean transition(UUID id, CommandStatus from, CommandStatus to, Instant now);

    void markStaleExecutingUnconfirmed();
}
