package com.example.controltower.adapter.out.persistence;

import com.example.controltower.application.port.out.Incidents;
import com.example.controltower.domain.Incident;
import com.example.controltower.domain.Severity;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcIncidents implements Incidents {

    private final JdbcTemplate jdbc;

    public JdbcIncidents(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Incident> open() {
        return jdbc.query(
                "select * from incidents where closed_at is null order by opened_at, id", JdbcIncidents::row);
    }

    @Override
    public void open(Incident i) {
        jdbc.update(
                "insert into incidents (id, equipment_id, kind, severity, opened_at, closed_at) values (?, ?, ?, ?, ?, ?)"
                        + " on conflict (equipment_id, kind) where closed_at is null do nothing",
                i.id(),
                i.equipmentId(),
                i.kind(),
                i.severity().name(),
                SqlTime.bind(i.openedAt()),
                SqlTime.bind(i.closedAt()));
    }

    @Override
    public void close(UUID id, Instant at) {
        jdbc.update("update incidents set closed_at = ? where id = ? and closed_at is null", SqlTime.bind(at), id);
    }

    @Override
    public void regrade(UUID id, Severity severity) {
        jdbc.update("update incidents set severity = ? where id = ?", severity.name(), id);
    }

    private static Incident row(ResultSet rs, int rowNum) throws SQLException {
        return new Incident(
                rs.getObject("id", UUID.class),
                rs.getString("equipment_id"),
                rs.getString("kind"),
                Severity.valueOf(rs.getString("severity")),
                SqlTime.read(rs, "opened_at"),
                SqlTime.read(rs, "closed_at"));
    }
}
