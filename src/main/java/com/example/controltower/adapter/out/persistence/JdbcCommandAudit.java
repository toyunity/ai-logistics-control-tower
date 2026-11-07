package com.example.controltower.adapter.out.persistence;

import com.example.controltower.application.port.out.CommandAudit;
import com.example.controltower.domain.CommandSpec;
import com.example.controltower.domain.CommandStatus;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcCommandAudit implements CommandAudit {

    private final JdbcTemplate jdbc;

    public JdbcCommandAudit(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void record(UUID commandId, String accountId, CommandSpec spec, CommandStatus result, Instant at) {
        jdbc.update(
                "insert into command_audit (command_id, account_id, spec, result, recorded_at) values (?, ?, ?, ?, ?)",
                commandId,
                accountId,
                SpecJson.write(spec),
                result.name(),
                SqlTime.bind(at));
    }
}
