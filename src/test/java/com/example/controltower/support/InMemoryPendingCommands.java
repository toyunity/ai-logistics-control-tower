package com.example.controltower.support;

import com.example.controltower.application.port.out.PendingCommands;
import com.example.controltower.domain.CommandSpec;
import com.example.controltower.domain.CommandStatus;
import com.example.controltower.domain.PendingCommand;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class InMemoryPendingCommands implements PendingCommands {

    private final Map<UUID, PendingCommand> commands = new LinkedHashMap<>();
    private final InMemoryCommandAudit audit;
    private int reads;
    private int saves;

    public InMemoryPendingCommands() {
        this(new InMemoryCommandAudit());
    }

    public InMemoryPendingCommands(InMemoryCommandAudit audit) {
        this.audit = audit;
    }

    public int reads() {
        return reads;
    }

    public int saves() {
        return saves;
    }

    public synchronized List<PendingCommand> all() {
        return List.copyOf(commands.values());
    }

    @Override
    public synchronized void save(PendingCommand command) {
        saves++;
        commands.put(command.id(), command);
    }

    @Override
    public synchronized Optional<PendingCommand> find(UUID id) {
        reads++;
        return Optional.ofNullable(commands.get(id));
    }

    @Override
    public synchronized Optional<PendingCommand> findLive(
            String accountId, CommandSpec.Kind kind, String targetId, Instant now) {
        reads++;
        return commands.values().stream()
                .filter(c -> c.status() == CommandStatus.PENDING && c.expiresAt().isAfter(now))
                .filter(c -> c.accountId().equals(accountId))
                .filter(c -> c.spec().kind() == kind && c.spec().targetId().equals(targetId))
                .findFirst();
    }

    @Override
    public synchronized boolean transition(UUID id, CommandStatus from, CommandStatus to, Instant now) {
        PendingCommand c = commands.get(id);
        if (c == null || c.status() != from) {
            return false;
        }
        if (from == CommandStatus.PENDING && !c.expiresAt().isAfter(now)) {
            return false;
        }
        commands.put(id, withStatus(c, to));
        return true;
    }

    @Override
    public synchronized void markStaleExecutingUnconfirmed() {
        commands.replaceAll((id, c) ->
                c.status() == CommandStatus.EXECUTING ? withStatus(c, CommandStatus.UNCONFIRMED) : c);
    }

    private static PendingCommand withStatus(PendingCommand c, CommandStatus status) {
        return new PendingCommand(
                c.id(), c.accountId(), c.spec(), c.stateDigest(), c.impact(), status, c.createdAt(), c.expiresAt());
    }
}
