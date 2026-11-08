package com.example.controltower.adapter.in.tool;

import com.example.controltower.application.port.in.PlantStatus;
import com.example.controltower.application.port.in.ProposeCommand;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

public class PlantTools {

    private final PlantStatus status;
    private final ProposeCommand propose;

    public PlantTools(PlantStatus status, ProposeCommand propose) {
        this.status = status;
        this.propose = propose;
    }

    @Tool(description = "라인 전체 설비 상태와 열린 장애 건, 열린 정비 요청을 조회한다.")
    public ToolResults getPlantStatus(ToolContext ctx) {
        throw new UnsupportedOperationException();
    }

    @Tool(description = "설비 하나의 현재 상태를 조회한다.")
    public ToolResults getEquipment(@ToolParam(description = "설비 ID. 예: CV-3, CH-03, AMR-01, SR-1, SC-1") String id, ToolContext ctx) {
        throw new UnsupportedOperationException();
    }

    @Tool(description = "설비의 최근 이벤트를 시간순으로 조회한다.")
    public ToolResults getTimeline(
            @ToolParam(description = "설비 ID") String equipmentId,
            @ToolParam(description = "조회할 최근 분 수. 1~120, 생략하면 30", required = false) Integer minutes,
            ToolContext ctx) {
        throw new UnsupportedOperationException();
    }

    @Tool(description = "이벤트 순서로 추정한 원인 후보 설비와 근거 이벤트를 조회한다. 확정된 원인이 아니라 후보다.")
    public ToolResults diagnose(@ToolParam(description = "증상이 보이는 설비 ID") String equipmentId, ToolContext ctx) {
        throw new UnsupportedOperationException();
    }

    @Tool(description = "컨베이어 재가동을 제안한다. 실행하지 않고 확인 카드만 만든다.")
    public ToolResults proposeRestart(@ToolParam(description = "컨베이어 ID. 예: CV-3") String conveyorId, ToolContext ctx) {
        throw new UnsupportedOperationException();
    }

    @Tool(description = "슈트 물량 우회를 제안한다. 실행하지 않고 확인 카드만 만든다.")
    public ToolResults proposeDivert(
            @ToolParam(description = "우회할 원본 슈트 ID. 예: CH-03") String fromChute,
            @ToolParam(description = "물량을 받을 대상 슈트 ID. 예: CH-04") String toChute,
            ToolContext ctx) {
        throw new UnsupportedOperationException();
    }

    @Tool(description = "로봇 작업 재배정을 제안한다. 실행하지 않고 확인 카드만 만든다.")
    public ToolResults proposeReassign(
            @ToolParam(description = "작업을 넘길 원본 로봇 ID. 예: AMR-01") String fromRobot,
            @ToolParam(description = "작업을 받을 대상 로봇 ID. 예: AMR-02") String toRobot,
            ToolContext ctx) {
        throw new UnsupportedOperationException();
    }

    @Tool(description = "설비 정비 요청 등록을 제안한다. 실행하지 않고 확인 카드만 만든다.")
    public ToolResults proposeMaintenance(
            @ToolParam(description = "정비할 설비 ID") String equipmentId,
            @ToolParam(description = "정비가 필요한 이유 한두 문장") String summary,
            ToolContext ctx) {
        throw new UnsupportedOperationException();
    }
}
