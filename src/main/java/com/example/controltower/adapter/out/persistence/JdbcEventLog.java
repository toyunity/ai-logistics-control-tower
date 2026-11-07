package com.example.controltower.adapter.out.persistence;

import com.example.controltower.application.port.out.EventLog;
import com.example.controltower.domain.EquipmentEvent;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcEventLog implements EventLog {

    // 포트가 저장소와 무관한 동률 순서를 약속한다. DB 기본 정렬 규칙은 환경마다 달라 코드 순서로 고정한다.
    private static final String ASCENDING = " order by occurred_at, event_id collate \"C\"";
    private static final String LAST = " order by occurred_at desc, event_id collate \"C\" desc limit 1";

    private final JdbcTemplate jdbc;

    public JdbcEventLog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean append(EquipmentEvent e) {
        return jdbc.update(
                        "insert into equipment_events (event_id, equipment_id, code, value, occurred_at)"
                                + " values (?, ?, ?, ?, ?) on conflict (event_id) do nothing",
                        e.eventId(),
                        e.equipmentId(),
                        e.code(),
                        e.value(),
                        SqlTime.bind(e.occurredAt()))
                == 1;
    }

    @Override
    public List<EquipmentEvent> between(String equipmentId, Instant from, Instant to) {
        return jdbc.query(
                "select * from equipment_events where equipment_id = ? and occurred_at >= ? and occurred_at <= ?" + ASCENDING,
                JdbcEventLog::row,
                equipmentId,
                SqlTime.bind(from),
                SqlTime.bind(to));
    }

    @Override
    public List<EquipmentEvent> since(Instant from) {
        return jdbc.query(
                "select * from equipment_events where occurred_at >= ?" + ASCENDING, JdbcEventLog::row, SqlTime.bind(from));
    }

    @Override
    public Optional<EquipmentEvent> latest(String equipmentId) {
        return jdbc.query("select * from equipment_events where equipment_id = ?" + LAST, JdbcEventLog::row, equipmentId)
                .stream()
                .findFirst();
    }

    private static EquipmentEvent row(ResultSet rs, int rowNum) throws SQLException {
        return new EquipmentEvent(
                rs.getString("event_id"),
                rs.getString("equipment_id"),
                rs.getString("code"),
                rs.getObject("value", Integer.class),
                SqlTime.read(rs, "occurred_at"));
    }
}
