package com.example.controltower.config;

import com.example.controltower.application.port.in.CommandResult;
import com.example.controltower.application.port.in.DecideCommand;
import com.example.controltower.application.port.in.ProposalResult;
import com.example.controltower.application.port.in.ProposeCommand;
import com.example.controltower.domain.Actor;
import com.example.controltower.domain.CommandSpec;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 코어가 Micrometer를 모르게 입력 포트를 감싸 관측한다. 태그는 열거형 이름뿐이다(카드 요약, ID, 계정은 넣지 않는다).
 * 예외는 관측에 넘기지 않는다. 넘기면 예외 문구(SQL 값 등)가 추적의 예외 이벤트로 나간다. 대신 outcome=ERROR로 남긴다.
 */
final class ObservedCommands {

    static final String PROPOSE = "controltower.command.propose";
    static final String DECIDE = "controltower.command.decide";
    private static final String ERROR = "ERROR";

    private ObservedCommands() {}

    record Propose(ProposeCommand delegate, ObservationRegistry registry) implements ProposeCommand {

        @Override
        public ProposalResult propose(CommandSpec spec, Actor actor) {
            Observation observation = Observation.createNotStarted(PROPOSE, registry)
                    .lowCardinalityKeyValue("kind", spec == null ? "UNKNOWN" : spec.kind().name())
                    .start();
            String outcome = ERROR;
            try (var scope = observation.openScope()) {
                ProposalResult result = delegate.propose(spec, actor);
                outcome = switch (result) {
                    case ProposalResult.Proposed p -> "PROPOSED";
                    case ProposalResult.Rejected r -> r.code().name();
                };
                return result;
            } finally {
                observation.lowCardinalityKeyValue("outcome", outcome).stop();
            }
        }
    }

    record Decide(DecideCommand delegate, ObservationRegistry registry) implements DecideCommand {

        @Override
        public CommandResult confirm(UUID id, Actor actor) {
            return observe("CONFIRM", () -> delegate.confirm(id, actor));
        }

        @Override
        public CommandResult reject(UUID id, Actor actor) {
            return observe("REJECT", () -> delegate.reject(id, actor));
        }

        // NOT_FOUND와 FORBIDDEN은 status가 null이라 NONE이 된다. 남의 카드 상태가 지표로도 드러나지 않는다.
        private CommandResult observe(String kind, Supplier<CommandResult> call) {
            Observation observation = Observation.createNotStarted(DECIDE, registry)
                    .lowCardinalityKeyValue("kind", kind)
                    .start();
            String outcome = ERROR;
            String status = "NONE";
            try (var scope = observation.openScope()) {
                CommandResult result = call.get();
                outcome = result.outcome().name();
                status = result.status() == null ? "NONE" : result.status().name();
                return result;
            } finally {
                observation.lowCardinalityKeyValue("outcome", outcome)
                        .lowCardinalityKeyValue("status", status)
                        .stop();
            }
        }
    }
}
