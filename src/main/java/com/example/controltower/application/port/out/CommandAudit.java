package com.example.controltower.application.port.out;

import com.example.controltower.domain.CommandSpec;
import com.example.controltower.domain.CommandStatus;
import java.time.Instant;
import java.util.UUID;

public interface CommandAudit {

    void record(UUID commandId, String accountId, CommandSpec spec, CommandStatus result, Instant at);
}
