package com.example.controltower.adapter.in.tool;

import static com.example.controltower.adapter.in.tool.ToolResults.LIMIT_EXCEEDED;
import static com.example.controltower.adapter.in.tool.ToolResults.NOT_FOUND;
import static com.example.controltower.adapter.in.tool.ToolResults.NOT_PERMITTED;
import static com.example.controltower.adapter.in.tool.ToolResults.UNAVAILABLE;

import com.example.controltower.adapter.in.tool.ToolResults.Denied;
import com.example.controltower.adapter.in.tool.ToolResults.Ok;
import com.example.controltower.application.port.in.EquipmentView;
import com.example.controltower.application.port.in.PlantSnapshot;
import com.example.controltower.application.port.in.PlantStatus;
import com.example.controltower.application.port.in.ProposalResult;
import com.example.controltower.application.port.in.ProposeCommand;
import com.example.controltower.application.port.in.TurnScope;
import com.example.controltower.domain.CommandSpec;
import com.example.controltower.domain.DenialCode;
import com.example.controltower.domain.EquipmentEvent;
import com.example.controltower.domain.Incident;
import com.example.controltower.domain.LineTopology;
import com.example.controltower.domain.PendingCommand;
import com.example.controltower.domain.RootCauseCandidate;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.ai.util.json.JsonParser;

/** 도구는 예외를 던지지 않는다. 제안 도구는 대기 카드만 만들고, 계정은 인자가 아니라 TurnScope에서 읽는다. */
public class PlantTools {

    private static final Logger log = LoggerFactory.getLogger(PlantTools.class);
    private static final int DEFAULT_MINUTES = 30;
    private static final int MAX_SUMMARY = 500;

    private final LineTopology topology = LineTopology.DEFAULT;
    private final PlantStatus status;
    private final ProposeCommand propose;

    public PlantTools(PlantStatus status, ProposeCommand propose) {
        this.status = status;
        this.propose = propose;
    }

    /** 운영에 등록하는 콜백이다. 인자 변환은 메서드 본문 전에 실패하므로 한도는 콜백 단계에서 센다. */
    public ToolCallback[] callbacks() {
        return Arrays.stream(ToolCallbacks.from(this)).map(Counted::new).toArray(ToolCallback[]::new);
    }

    private record Counted(ToolCallback delegate) implements ToolCallback {

        private static final String LIMIT = JsonParser.toJson(LIMIT_EXCEEDED);

        @Override
        public ToolDefinition getToolDefinition() {
            return delegate.getToolDefinition();
        }

        @Override
        public ToolMetadata getToolMetadata() {
            return delegate.getToolMetadata();
        }

        @Override
        public String call(String input) {
            return delegate.call(input);
        }

        // 두 배를 넘으면 tryCount가 던지는 BudgetExhausted는 MethodToolCallback을 거치지 않아 그대로 턴을 끝낸다.
        @Override
        public String call(String input, ToolContext ctx) {
            if (ctx != null && ctx.getContext().get(TurnScope.KEY) instanceof TurnScope scope && !scope.tryCount()) {
                return LIMIT;
            }
            return delegate.call(input, ctx);
        }
    }

    @Tool(description = "라인 전체 설비 상태와 열린 장애 건, 열린 정비 요청을 조회한다.")
    public ToolResults getPlantStatus(ToolContext ctx) {
        return run(ctx, scope -> new Ok(StatusView.of(status.snapshot())));
    }

    @Tool(description = "설비 하나의 현재 상태를 조회한다.")
    public ToolResults getEquipment(@ToolParam(description = "설비 ID. 예: CV-3, CH-03, AMR-01, SR-1, SC-1") String id, ToolContext ctx) {
        return run(ctx, scope -> !topology.contains(id)
                ? NOT_FOUND
                : status.equipment(id).<ToolResults>map(Ok::new).orElse(NOT_FOUND));
    }

    @Tool(description = "설비의 최근 이벤트를 시간순으로 조회한다.")
    public ToolResults getTimeline(
            @ToolParam(description = "설비 ID") String equipmentId,
            @ToolParam(description = "조회할 최근 분 수. 1~120, 생략하면 30", required = false) Integer minutes,
            ToolContext ctx) {
        return run(ctx, scope -> !topology.contains(equipmentId)
                ? NOT_FOUND
                : new Ok(status.timeline(equipmentId, minutes == null ? DEFAULT_MINUTES : minutes).stream()
                        .map(EventView::of)
                        .toList()));
    }

    @Tool(description = "이벤트 순서로 추정한 원인 후보 설비와 근거 이벤트를 조회한다. 확정된 원인이 아니라 후보다.")
    public ToolResults diagnose(@ToolParam(description = "증상이 보이는 설비 ID") String equipmentId, ToolContext ctx) {
        return run(ctx, scope -> !topology.contains(equipmentId)
                ? NOT_FOUND
                : new Ok(status.diagnose(equipmentId).map(DiagnosisView::of).orElse(DiagnosisView.NONE)));
    }

    @Tool(description = "컨베이어 재가동을 제안한다. 실행하지 않고 확인 카드만 만든다.")
    public ToolResults proposeRestart(@ToolParam(description = "컨베이어 ID. 예: CV-3") String conveyorId, ToolContext ctx) {
        return run(ctx, scope -> !topology.isConveyor(conveyorId)
                ? NOT_FOUND
                : propose(scope, new CommandSpec.Restart(conveyorId)));
    }

    @Tool(description = "슈트 물량 우회를 제안한다. 실행하지 않고 확인 카드만 만든다.")
    public ToolResults proposeDivert(
            @ToolParam(description = "우회할 원본 슈트 ID. 예: CH-03") String fromChute,
            @ToolParam(description = "물량을 받을 대상 슈트 ID. 예: CH-04") String toChute,
            ToolContext ctx) {
        return run(ctx, scope -> !topology.isChute(fromChute) || !topology.isChute(toChute)
                ? NOT_FOUND
                : propose(scope, new CommandSpec.Divert(fromChute, toChute)));
    }

    @Tool(description = "로봇 작업 재배정을 제안한다. 실행하지 않고 확인 카드만 만든다.")
    public ToolResults proposeReassign(
            @ToolParam(description = "작업을 넘길 원본 로봇 ID. 예: AMR-01") String fromRobot,
            @ToolParam(description = "작업을 받을 대상 로봇 ID. 예: AMR-02") String toRobot,
            ToolContext ctx) {
        return run(ctx, scope -> !topology.isRobot(fromRobot) || !topology.isRobot(toRobot)
                ? NOT_FOUND
                : propose(scope, new CommandSpec.Reassign(fromRobot, toRobot)));
    }

    @Tool(description = "설비 정비 요청 등록을 제안한다. 실행하지 않고 확인 카드만 만든다.")
    public ToolResults proposeMaintenance(
            @ToolParam(description = "정비할 설비 ID") String equipmentId,
            @ToolParam(description = "정비가 필요한 이유 한두 문장") String summary,
            ToolContext ctx) {
        return run(ctx, scope -> !topology.contains(equipmentId)
                ? NOT_FOUND
                : propose(scope, new CommandSpec.Maintenance(equipmentId, clip(summary))));
    }

    // 인자는 모델이 만든 값이라 기록하지 않는다. 예외 내용도 모델에게 넘기지 않는다.
    private static ToolResults run(ToolContext ctx, Function<TurnScope, ToolResults> body) {
        if (ctx == null || !(ctx.getContext().get(TurnScope.KEY) instanceof TurnScope scope)) {
            return UNAVAILABLE;
        }
        try {
            return body.apply(scope);
        } catch (RuntimeException e) {
            log.warn("tool call failed: {}", e.getClass().getName());
            return UNAVAILABLE;
        }
    }

    private ToolResults propose(TurnScope scope, CommandSpec spec) {
        return switch (propose.propose(spec, scope.actor())) {
            case ProposalResult.Proposed(PendingCommand card) -> {
                scope.add(card);
                yield new Ok(ProposalView.of(card));
            }
            case ProposalResult.Rejected(DenialCode code) -> switch (code) {
                case NOT_FOUND -> NOT_FOUND;
                case NOT_PERMITTED -> NOT_PERMITTED;
                default -> new Denied(code.name(), code.message());
            };
        };
    }

    private static String clip(String summary) {
        String text = summary == null ? "" : summary.strip();
        return text.length() > MAX_SUMMARY ? text.substring(0, MAX_SUMMARY) : text;
    }

    // 아래 형태들이 모델에게 가는 값이다. 계정, 이벤트 ID, 모델이 쓴 정비 요약은 넣지 않는다.

    record StatusView(List<EquipmentView> equipment, List<Incident> incidents, List<MaintenanceView> maintenance, Instant at) {
        static StatusView of(PlantSnapshot s) {
            return new StatusView(s.equipment(), s.incidents(),
                    s.maintenance().stream().map(m -> new MaintenanceView(m.id(), m.equipmentId(), m.openedAt())).toList(),
                    s.at());
        }
    }

    record MaintenanceView(UUID id, String equipmentId, Instant openedAt) {}

    record EventView(String equipmentId, String code, Integer value, Instant occurredAt) {
        static EventView of(EquipmentEvent e) {
            return new EventView(e.equipmentId(), e.code(), e.value(), e.occurredAt());
        }
    }

    record DiagnosisView(String candidateId, List<EventView> evidence) {
        static final DiagnosisView NONE = new DiagnosisView(null, List.of());

        static DiagnosisView of(RootCauseCandidate c) {
            return new DiagnosisView(c.equipmentId(), c.evidence().stream().map(EventView::of).toList());
        }
    }

    record ProposalView(String kind, String targetId, String impact, Instant expiresAt, String next) {
        static ProposalView of(PendingCommand card) {
            return new ProposalView(card.spec().kind().name(), card.spec().targetId(), card.impact(), card.expiresAt(),
                    "실행되지 않았습니다. 사용자가 화면의 확인 카드에서 확정해야 실행됩니다.");
        }
    }
}
