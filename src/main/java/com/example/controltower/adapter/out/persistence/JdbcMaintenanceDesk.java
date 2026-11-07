package com.example.controltower.adapter.out.persistence;

import com.example.controltower.application.port.out.MaintenanceDesk;
import com.example.controltower.domain.GatewayUnavailableException;
import com.example.controltower.domain.MaintenanceRequest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcMaintenanceDesk implements MaintenanceDesk {

    private final JdbcTemplate jdbc;

    public JdbcMaintenanceDesk(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean hasOpen(String equipmentId) {
        try {
            return Boolean.TRUE.equals(jdbc.queryForObject(
                    "select exists (select 1 from maintenance_requests where equipment_id = ? and closed_at is null)",
                    Boolean.class,
                    equipmentId));
        } catch (DataAccessException e) {
            // 제안 평가가 이 예외를 STATE_UNKNOWN으로 막는다.
            throw new GatewayUnavailableException("maintenance desk unavailable", e);
        }
    }

    /** 충돌만 전송 실패로 번역한다. 다른 DB 오류는 등록 여부를 모르므로 그대로 던져 UNCONFIRMED로 닫히게 한다. */
    @Override
    public UUID open(String equipmentId, String summary, Instant now) {
        UUID id = UUID.randomUUID();
        int inserted = jdbc.update(
                "insert into maintenance_requests (id, equipment_id, summary, opened_at) values (?, ?, ?, ?)"
                        + " on conflict (equipment_id) where closed_at is null do nothing",
                id,
                equipmentId,
                summary,
                SqlTime.bind(now));
        if (inserted == 0) {
            throw new GatewayUnavailableException("maintenance request already open", null);
        }
        return id;
    }

    @Override
    public boolean close(UUID id, Instant now) {
        return jdbc.update(
                        "update maintenance_requests set closed_at = ? where id = ? and closed_at is null",
                        SqlTime.bind(now),
                        id)
                == 1;
    }

    @Override
    public List<MaintenanceRequest> openRequests() {
        return jdbc.query(
                "select * from maintenance_requests where closed_at is null order by opened_at, id",
                JdbcMaintenanceDesk::row);
    }

    private static MaintenanceRequest row(ResultSet rs, int rowNum) throws SQLException {
        return new MaintenanceRequest(
                rs.getObject("id", UUID.class),
                rs.getString("equipment_id"),
                rs.getString("summary"),
                SqlTime.read(rs, "opened_at"),
                SqlTime.read(rs, "closed_at"));
    }
}
