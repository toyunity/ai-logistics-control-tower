package com.example.controltower.support;

import com.example.controltower.application.port.out.CommandAudit;
import com.example.controltower.domain.CommandSpec;
import com.example.controltower.domain.CommandStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class InMemoryCommandAudit implements CommandAudit {

    public record Entry(UUID commandId, String accountId, CommandSpec spec, CommandStatus result, Instant at) {}

    private final List<Entry> entries = new ArrayList<>();

    public List<Entry> entries() {
        return List.copyOf(entries);
    }

    @Override
    public void record(UUID commandId, String accountId, CommandSpec spec, CommandStatus result, Instant at) {
        entries.add(new Entry(commandId, accountId, spec, result, at));
    }
}
