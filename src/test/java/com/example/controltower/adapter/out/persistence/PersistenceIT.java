package com.example.controltower.adapter.out.persistence;

import static com.example.controltower.adapter.out.persistence.PendingCommandsContract.RESTART;
import static com.example.controltower.adapter.out.persistence.PendingCommandsContract.T0;
import static com.example.controltower.adapter.out.persistence.PendingCommandsContract.card;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.controltower.application.port.out.CommandAudit;
import com.example.controltower.application.port.out.EventLog;
import com.example.controltower.application.port.out.Incidents;
import com.example.controltower.application.port.out.MaintenanceDesk;
import com.example.controltower.application.port.out.PendingCommands;
import com.example.controltower.application.port.out.UnitOfWork;
import com.example.controltower.domain.CommandSpec;
import com.example.controltower.domain.CommandStatus;
import com.example.controltower.domain.EquipmentEvent;
import com.example.controltower.domain.GatewayUnavailableException;
import com.example.controltower.domain.Incident;
import com.example.controltower.domain.PendingCommand;
import com.example.controltower.domain.Severity;
import com.example.controltower.support.FakeAiConfig;
import com.example.controltower.support.TestInfraConfig;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

@SpringBootTest
@Import({TestInfraConfig.class, FakeAiConfig.class})
@Timeout(60)
class PersistenceIT {

    @Autowired PendingCommands pending;
    @Autowired EventLog events;
    @Autowired Incidents incidents;
    @Autowired MaintenanceDesk desk;
    @Autowired CommandAudit audit;
    @Autowired UnitOfWork unitOfWork;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired ApplicationContext ctx;

    // 컨텍스트를 공유하는 테스트들과 컨테이너가 같고, 기동 정리는 모든 EXECUTING 행을 건드린다.
    @BeforeEach
    void clearTables() {
        jdbc.execute("truncate pending_commands, equipment_events, incidents, maintenance_requests, command_audit");
    }

    private List<CommandStatus> auditedResults(UUID commandId) {
        return jdbc.queryForList(
                        "select result from command_audit where command_id = ? order by id", String.class, commandId)
                .stream()
                .map(CommandStatus::valueOf)
                .toList();
    }

    private CommandStatus statusOf(PendingCommand c) {
        return pending.find(c.id()).orElseThrow().status();
    }

    private PendingCommand saved(String account, CommandSpec spec) {
        PendingCommand c = card(account, spec);
        pending.save(c);
        return c;
    }

    @Nested
    class PendingCommandsRepository extends PendingCommandsContract {
        @Override
        PendingCommands subject() {
            return pending;
        }

        @Override
        List<CommandStatus> auditedResults(UUID commandId) {
            return PersistenceIT.this.auditedResults(commandId);
        }
    }

    @Nested
    class EventLogRepository extends EventLogContract {
        @Override
        EventLog subject() {
            return events;
        }
    }

    @Nested
    class IncidentsRepository extends IncidentsContract {
        @Override
        Incidents subject() {
            return incidents;
        }
    }

    @Nested
    class MaintenanceDeskRepository extends MaintenanceDeskContract {
        @Override
        MaintenanceDesk subject() {
            return desk;
        }
    }

    @Test
    void concurrentConfirm_oneWins() throws Exception {
        PendingCommand c = saved("op-1", RESTART);

        assertThat(claimConcurrently(List.of(c, c, c, c, c, c, c, c))).containsOnlyOnce(true);

        assertThat(statusOf(c)).isEqualTo(CommandStatus.EXECUTING);
    }

    @Test
    void concurrentConfirmOfDifferentCardsOnOneTarget_oneWins() throws Exception {
        List<PendingCommand> cards = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            cards.add(saved("op-" + i, RESTART));
        }

        assertThat(claimConcurrently(cards)).containsOnlyOnce(true);

        assertThat(jdbc.queryForObject(
                        "select count(*) from pending_commands where status = 'EXECUTING'", Long.class))
                .isEqualTo(1);
    }

    @Test
    void concurrentConfirmOfDivertsToOneDestination_oneWins() throws Exception {
        PendingCommand fromCh03 = saved("op-1", new CommandSpec.Divert("CH-03", "CH-04"));
        PendingCommand fromCh01 = saved("op-2", new CommandSpec.Divert("CH-01", "CH-04"));

        assertThat(claimConcurrently(List.of(fromCh03, fromCh01))).containsOnlyOnce(true);

        assertThat(jdbc.queryForObject(
                        "select count(*) from pending_commands where status = 'EXECUTING'", Long.class))
                .isEqualTo(1);
    }

    private List<Boolean> claimConcurrently(List<PendingCommand> cards) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> claims = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(cards.size())) {
            for (PendingCommand c : cards) {
                claims.add(pool.submit(() -> {
                    start.await();
                    return unitOfWork.inTransaction(() -> pending.transition(
                            c.id(), CommandStatus.PENDING, CommandStatus.EXECUTING, T0));
                }));
            }
            start.countDown();
            List<Boolean> results = new ArrayList<>();
            for (Future<Boolean> claim : claims) {
                results.add(claim.get());
            }
            return results;
        }
    }

    // 선점이 NOT EXISTS를 통과한 뒤 유일 인덱스에서 막히는 경쟁. 패자의 트랜잭션이 중단되면 false를 돌려줘도 커밋이 실패한다.
    @Test
    void claimLosingAtTheUniqueIndex_isFalse_andTheTransactionStillCommits() throws Exception {
        PendingCommand winner = saved("op-1", RESTART);
        PendingCommand loser = saved("op-2", RESTART);
        CountDownLatch winnerClaimed = new CountDownLatch(1);
        CountDownLatch releaseWinner = new CountDownLatch(1);

        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<Boolean> first = pool.submit(() -> unitOfWork.inTransaction(() -> {
                boolean won = pending.transition(winner.id(), CommandStatus.PENDING, CommandStatus.EXECUTING, T0);
                winnerClaimed.countDown();
                await(releaseWinner);
                return won;
            }));
            winnerClaimed.await();
            Future<Boolean> second = pool.submit(() -> unitOfWork.inTransaction(() -> {
                audit.record(loser.id(), loser.accountId(), loser.spec(), CommandStatus.FAILED, T0);
                boolean won = pending.transition(loser.id(), CommandStatus.PENDING, CommandStatus.EXECUTING, T0);
                audit.record(loser.id(), loser.accountId(), loser.spec(), CommandStatus.REJECTED, T0);
                return won;
            }));
            awaitClaimBlockedOnIndex();
            releaseWinner.countDown();

            assertThat(first.get()).isTrue();
            assertThat(second.get()).isFalse();
        }

        assertThat(statusOf(winner)).isEqualTo(CommandStatus.EXECUTING);
        assertThat(statusOf(loser)).isEqualTo(CommandStatus.PENDING);
        assertThat(auditedResults(loser.id())).containsExactly(CommandStatus.FAILED, CommandStatus.REJECTED);
    }

    private void awaitClaimBlockedOnIndex() throws InterruptedException {
        String blocked = "select count(*) from pg_stat_activity"
                + " where wait_event_type = 'Lock' and query like 'update pending_commands%'";
        while (jdbc.queryForObject(blocked, Long.class) == 0) {
            Thread.sleep(20);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void refusedClaimInATransaction_stillCommits() {
        PendingCommand first = saved("op-1", RESTART);
        PendingCommand second = saved("op-2", RESTART);
        pending.transition(first.id(), CommandStatus.PENDING, CommandStatus.EXECUTING, T0);

        assertStillCommitsAfter(() -> assertThat(
                        pending.transition(second.id(), CommandStatus.PENDING, CommandStatus.EXECUTING, T0))
                .isFalse());
    }

    @Test
    void duplicateEventInATransaction_stillCommits() {
        EquipmentEvent event = new EquipmentEvent("e1", "CV-1", "STOPPED", null, T0);
        events.append(event);

        assertStillCommitsAfter(() -> assertThat(events.append(event)).isFalse());
    }

    @Test
    void duplicateIncidentInATransaction_stillCommits() {
        incidents.open(new Incident(UUID.randomUUID(), "CV-1", "CHUTE_FULL", Severity.SEV3, T0, null));

        assertStillCommitsAfter(() ->
                incidents.open(new Incident(UUID.randomUUID(), "CV-1", "CHUTE_FULL", Severity.SEV1, T0, null)));

        assertThat(incidents.open()).extracting(Incident::severity).containsExactly(Severity.SEV3);
    }

    @Test
    void maintenanceConflictInATransaction_stillCommits() {
        desk.open("CV-1", "벨트 소음", T0);

        assertStillCommitsAfter(() -> assertThatThrownBy(() -> desk.open("CV-1", "다른 요청", T0))
                .isExactlyInstanceOf(GatewayUnavailableException.class));
    }

    private void assertStillCommitsAfter(Runnable conflict) {
        EquipmentEvent marker = new EquipmentEvent("marker", "MARKER", "STOPPED", null, T0);

        unitOfWork.inTransaction(() -> {
            conflict.run();
            return events.append(marker);
        });

        assertThat(events.latest("MARKER")).contains(marker);
    }

    @Test
    void unitOfWork_rollsBackEverythingOnFailure() {
        EquipmentEvent event = new EquipmentEvent("e1", "CV-1", "STOPPED", null, T0);

        assertThatThrownBy(() -> unitOfWork.inTransaction(() -> {
                    events.append(event);
                    audit.record(UUID.randomUUID(), "op-1", RESTART, CommandStatus.FAILED, T0);
                    throw new IllegalStateException("boom");
                }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(events.since(T0)).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from command_audit", Long.class)).isZero();
    }

    @Test
    void expiredCardDoesNotBlockANewProposal_inOneTransaction() {
        PendingCommand old = saved("op-1", RESTART);
        Instant now = old.expiresAt();
        PendingCommand fresh = card("op-1", RESTART, now, now.plusSeconds(120));

        unitOfWork.inTransaction(() -> {
            assertThat(pending.findLive("op-1", CommandSpec.Kind.RESTART, "CV-3", now)).isEmpty();
            pending.save(fresh);
            return null;
        });

        assertThat(statusOf(old)).isEqualTo(CommandStatus.EXPIRED);
        assertThat(statusOf(fresh)).isEqualTo(CommandStatus.PENDING);
    }

    @Test
    void refusedSave_undoesItsExpiryFlip() {
        PendingCommand expired = card("op-1", RESTART, T0.minusSeconds(300), T0.minusSeconds(180));
        pending.save(expired);
        // ID가 같아 삽입이 기본 키에서 막힌다. 만료 처리만 남으면 안 된다.
        PendingCommand clash = new PendingCommand(
                expired.id(), "op-1", RESTART, "digest", "영향 범위", CommandStatus.PENDING, T0, T0.plusSeconds(120));

        assertThatThrownBy(() -> pending.save(clash)).isInstanceOf(RuntimeException.class);

        assertThat(statusOf(expired)).isEqualTo(CommandStatus.PENDING);
    }

    @Test
    void staleExecutingMarkedUnconfirmedOnBoot() throws Exception {
        CommandSpec spec = new CommandSpec.Divert("CH-03", "CH-04");
        PendingCommand c = saved("op-1", spec);
        pending.transition(c.id(), CommandStatus.PENDING, CommandStatus.EXECUTING, T0);

        ctx.getBean("staleCommandCleanup", SmartInitializingSingleton.class).afterSingletonsInstantiated();

        assertThat(statusOf(c)).isEqualTo(CommandStatus.UNCONFIRMED);
        assertThat(jdbc.queryForMap("select account_id, result, spec from command_audit where command_id = ?", c.id()))
                .containsEntry("account_id", "op-1")
                .containsEntry("result", "UNCONFIRMED")
                .containsEntry("spec", jdbc.queryForObject(
                        "select spec from pending_commands where id = ?", String.class, c.id()));
    }

    @Test
    void auditKeepsTheSpecOfEveryResult() {
        UUID id = UUID.randomUUID();
        CommandSpec spec = new CommandSpec.Maintenance("CV-1", "벨트 소음");

        audit.record(id, "op-1", spec, CommandStatus.SUCCEEDED, T0);

        assertThat(jdbc.queryForObject("select spec from command_audit where command_id = ?", String.class, id))
                .contains("MAINTENANCE", "CV-1", "벨트 소음");
        assertThat(jdbc.queryForObject(
                        "select recorded_at = ? from command_audit where command_id = ?",
                        Boolean.class,
                        T0.atOffset(java.time.ZoneOffset.UTC),
                        id))
                .isTrue();
    }

    @Test
    void unknownSpecKindOnReadIsAnError() {
        PendingCommand c = saved("op-1", RESTART);
        jdbc.update("update pending_commands set spec = '{\"kind\":\"SELF_DESTRUCT\"}' where id = ?", c.id());

        assertThatThrownBy(() -> pending.find(c.id())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void noPersonalOrFreeTextFromDevices() {
        Map<String, Object> columns = jdbc.queryForList(
                        "select column_name, data_type from information_schema.columns"
                                + " where table_schema = current_schema() and table_name = 'equipment_events'")
                .stream()
                .collect(Collectors.toMap(r -> (String) r.get("column_name"), r -> r.get("data_type")));

        assertThat(columns)
                .containsOnly(
                        Map.entry("event_id", "text"),
                        Map.entry("equipment_id", "text"),
                        Map.entry("code", "text"),
                        Map.entry("value", "integer"),
                        Map.entry("occurred_at", "timestamp with time zone"));
    }

    @Test
    void hasOpen_databaseErrorIsAGatewayFailure() {
        jdbc.execute("alter table maintenance_requests rename to maintenance_requests_gone");
        try {
            assertThatThrownBy(() -> desk.hasOpen("CV-1")).isExactlyInstanceOf(GatewayUnavailableException.class);
        } finally {
            jdbc.execute("alter table maintenance_requests_gone rename to maintenance_requests");
        }
    }

    // 전송 결과를 모르면 UNCONFIRMED로 닫아야 하므로 충돌 외의 오류를 전송 실패로 바꾸면 안 된다.
    @Test
    void open_otherDatabaseErrorIsNotPassedOffAsAFailedSend() {
        jdbc.execute("alter table maintenance_requests rename to maintenance_requests_gone");
        try {
            assertThatThrownBy(() -> desk.open("CV-1", "벨트 소음", T0))
                    .isNotInstanceOf(GatewayUnavailableException.class);
        } finally {
            jdbc.execute("alter table maintenance_requests_gone rename to maintenance_requests");
        }
    }

    @Test
    void instantsKeepMicroseconds_andDropWhatTheColumnCannotHold() {
        Instant micros = Instant.parse("2026-11-07T01:02:03.123456Z");
        Instant nanos = Instant.parse("2026-11-07T01:02:03.123456789Z");
        events.append(new EquipmentEvent("e1", "CV-1", "STOPPED", null, micros));
        events.append(new EquipmentEvent("e2", "CV-2", "STOPPED", null, nanos));

        assertThat(events.latest("CV-1").orElseThrow().occurredAt()).isEqualTo(micros);
        assertThat(events.latest("CV-2").orElseThrow().occurredAt()).isEqualTo(micros);
    }

    @Test
    void schemaScriptCanRunAgain_andKeepsRows() {
        EquipmentEvent event = new EquipmentEvent("e1", "CV-1", "STOPPED", null, T0);
        events.append(event);

        new ResourceDatabasePopulator(new ClassPathResource("schema-control.sql")).execute(dataSource);

        assertThat(events.since(T0)).containsExactly(event);
    }

    // 실행 잠금 키가 생기기 전 스키마로 만든 DB에서 기동해도 키가 채워지고 이전 인덱스가 빠져야 한다.
    @Test
    void schemaScriptUpgradesATableWithoutLockKey() {
        saved("op-1", new CommandSpec.Divert("CH-03", "CH-04"));
        saved("op-1", new CommandSpec.Reassign("AMR-01", "AMR-02"));
        saved("op-1", RESTART);
        saved("op-1", new CommandSpec.Maintenance("SC-1", "점검"));
        jdbc.execute("alter table pending_commands drop column lock_key");
        jdbc.execute("create unique index pending_commands_one_executing"
                + " on pending_commands (target_id) where status = 'EXECUTING'");

        new ResourceDatabasePopulator(new ClassPathResource("schema-control.sql")).execute(dataSource);

        assertThat(jdbc.queryForList("select kind || '=' || lock_key from pending_commands order by kind", String.class))
                .containsExactly("DIVERT=SR-1", "MAINTENANCE=SC-1", "REASSIGN=AMR-FLEET", "RESTART=CV-3");
        assertThat(jdbc.queryForList(
                        "select indexname from pg_indexes where tablename = 'pending_commands' and indexname like '%executing%'",
                        String.class))
                .containsExactly("pending_commands_one_executing_lock");
    }

    // 수신 검증을 거치지 않은 행이 감지와 화면에 장치 원문을 흘리지 않게 DB도 같은 형식만 받는다.
    @Test
    void eventCodeOutsideTheIngestPattern_isRejectedByTheDatabase() {
        String insert = "insert into equipment_events (event_id, equipment_id, code, occurred_at) values (?, 'CV-1', ?, now())";
        jdbc.update(insert, "ok", "CONVEYOR_STOPPED");

        for (String code : List.of("stopped", "1STOP", "STOP-1", "A".repeat(41), "")) {
            assertThatThrownBy(() -> jdbc.update(insert, "bad-" + code, code))
                    .as(code)
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }
}
