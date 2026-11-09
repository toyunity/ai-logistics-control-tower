package com.example.controltower.adapter.out.persistence;

import com.example.controltower.application.port.out.PendingCommands;
import com.example.controltower.domain.CommandSpec;
import com.example.controltower.domain.CommandStatus;
import com.example.controltower.domain.PendingCommand;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class JdbcPendingCommands implements PendingCommands {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate atomically;
    private final TransactionTemplate savepoint;

    public JdbcPendingCommands(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.atomically = new TransactionTemplate(transactions);
        this.savepoint = new TransactionTemplate(transactions);
        this.savepoint.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    }

    @Override
    public void save(PendingCommand c) {
        atomically.executeWithoutResult(status -> {
            // 만료된 행은 PENDING으로 남아 유일 인덱스를 막는다. 기준 시각은 호출자가 findLive에 쓴 값과 같은 createdAt이다.
            jdbc.update(
                    "update pending_commands set status = 'EXPIRED'"
                            + " where account_id = ? and kind = ? and target_id = ? and status = 'PENDING' and expires_at <= ?",
                    c.accountId(),
                    c.spec().kind().name(),
                    c.spec().targetId(),
                    SqlTime.bind(c.createdAt()));
            jdbc.update(
                    "insert into pending_commands"
                            + " (id, account_id, kind, target_id, lock_key, spec, state_digest, impact, status, created_at, expires_at)"
                            + " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    c.id(),
                    c.accountId(),
                    c.spec().kind().name(),
                    c.spec().targetId(),
                    c.spec().lockKey(),
                    SpecJson.write(c.spec()),
                    c.stateDigest(),
                    c.impact(),
                    c.status().name(),
                    SqlTime.bind(c.createdAt()),
                    SqlTime.bind(c.expiresAt()));
        });
    }

    @Override
    public Optional<PendingCommand> find(UUID id) {
        return jdbc.query("select * from pending_commands where id = ?", JdbcPendingCommands::row, id).stream()
                .findFirst();
    }

    @Override
    public Optional<PendingCommand> findLive(String accountId, CommandSpec.Kind kind, String targetId, Instant now) {
        return jdbc.query(
                        "select * from pending_commands"
                                + " where account_id = ? and kind = ? and target_id = ? and status = 'PENDING' and expires_at > ?",
                        JdbcPendingCommands::row,
                        accountId,
                        kind.name(),
                        targetId,
                        SqlTime.bind(now))
                .stream()
                .findFirst();
    }

    @Override
    public boolean transition(UUID id, CommandStatus from, CommandStatus to, Instant now) {
        StringBuilder sql = new StringBuilder("update pending_commands set status = ? where id = ? and status = ?");
        List<Object> args = new ArrayList<>(List.of(to.name(), id, from.name()));
        if (from == CommandStatus.PENDING) {
            sql.append(" and expires_at > ?");
            args.add(SqlTime.bind(now));
        }
        if (to != CommandStatus.EXECUTING) {
            return jdbc.update(sql.toString(), args.toArray()) == 1;
        }
        sql.append(" and not exists (select 1 from pending_commands e"
                + " where e.lock_key = pending_commands.lock_key and e.status = 'EXECUTING')");
        // 동시 선점은 조건을 함께 통과해 유일 인덱스에서 갈린다. PostgreSQL은 위반한 트랜잭션 전체를 중단시키므로,
        // 세이브포인트로 되돌려 UnitOfWork가 false를 받고도 커밋할 수 있게 한다. catch가 execute 밖이어야 되돌려진다.
        try {
            return Boolean.TRUE.equals(savepoint.execute(status -> jdbc.update(sql.toString(), args.toArray()) == 1));
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    @Override
    public void markStaleExecutingUnconfirmed() {
        // 한 문장이라 상태와 감사가 함께 남거나 함께 남지 않는다.
        jdbc.update(
                """
                with closed as (
                    update pending_commands set status = 'UNCONFIRMED' where status = 'EXECUTING'
                    returning id, account_id, spec
                )
                insert into command_audit (command_id, account_id, spec, result, recorded_at)
                select id, account_id, spec, 'UNCONFIRMED', now() from closed
                """);
    }

    private static PendingCommand row(ResultSet rs, int rowNum) throws SQLException {
        return new PendingCommand(
                rs.getObject("id", UUID.class),
                rs.getString("account_id"),
                SpecJson.read(rs.getString("spec")),
                rs.getString("state_digest"),
                rs.getString("impact"),
                CommandStatus.valueOf(rs.getString("status")),
                SqlTime.read(rs, "created_at"),
                SqlTime.read(rs, "expires_at"));
    }
}
