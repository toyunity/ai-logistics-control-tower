package com.example.controltower.application.port.out;

import com.example.controltower.domain.CommandSpec;
import com.example.controltower.domain.CommandStatus;
import com.example.controltower.domain.PendingCommand;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface PendingCommands {

    /**
     * createdAt 기준으로 만료된 같은 키(계정, 종류, 대상)의 PENDING 카드를 EXPIRED로 닫고 저장한다.
     * 살아 있는 같은 키의 카드가 있으면 예외를 던진다. 호출자가 먼저 정리해도 동시 제안은 여기까지 올 수 있다.
     */
    void save(PendingCommand command);

    Optional<PendingCommand> find(UUID id);

    Optional<PendingCommand> findLive(String accountId, CommandSpec.Kind kind, String targetId, Instant now);

    /**
     * from이 PENDING이면 expires_at > now 조건도 함께 건다. 대상당 EXECUTING은 하나뿐이라 이미 있으면 false다.
     * false를 돌려줘도 진행 중인 트랜잭션은 커밋할 수 있다.
     */
    boolean transition(UUID id, CommandStatus from, CommandStatus to, Instant now);

    /** 모든 EXECUTING 행을 UNCONFIRMED로 닫고 행마다 감사 기록을 함께 남긴다. 전송 중 죽으면 다른 기록이 없다. */
    void markStaleExecutingUnconfirmed();
}
