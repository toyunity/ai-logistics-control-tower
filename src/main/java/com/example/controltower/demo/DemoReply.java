package com.example.controltower.demo;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 도구 결과를 고정 문구로 옮긴다. 결과에 든 문구는 코드가 만든 값(ID, 상태, 고정 거절 사유)뿐이다. */
final class DemoReply {

    static final String PREFIX = "[데모 응답 · 실제 AI 아님] ";

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String UNREADABLE = "도구 결과를 읽지 못했습니다. 잠시 후 다시 시도해 주세요.";

    private DemoReply() {}

    static String render(String toolName, String json) {
        try {
            return PREFIX + body(toolName, JSON.readTree(json));
        } catch (RuntimeException e) {
            return PREFIX + UNREADABLE;
        }
    }

    private static String body(String toolName, JsonNode result) {
        String status = result.path("status").asString("");
        if (!status.equals("OK")) {
            String message = result.path("message").asString(UNREADABLE);
            return status.equals("DENIED") ? "제안이 거절되었습니다. " + message : message;
        }
        JsonNode data = result.path("data");
        return switch (toolName) {
            case "diagnose" -> data.path("candidateId").isString()
                    ? "원인 후보는 " + data.get("candidateId").asString() + "입니다. 이벤트 순서로 추정한 후보이며 확정된 원인이 아닙니다."
                    : "이벤트 기록에서 원인 후보를 찾지 못했습니다.";
            case "getPlantStatus" -> plantStatus(data);
            // 데모 의도는 조회 도구 중 위 둘만 부른다.
            default -> toolName.startsWith("propose")
                    ? "확인 카드를 만들었습니다. 아직 실행되지 않았습니다. 아래 카드에서 확정해 주세요."
                    : "도구 결과를 받았습니다.";
        };
    }

    private static String plantStatus(JsonNode data) {
        StringBuilder abnormal = new StringBuilder();
        for (JsonNode e : data.path("equipment")) {
            if (!e.path("state").asString("").equals("RUNNING")) {
                abnormal.append(abnormal.isEmpty() ? "" : ", ")
                        .append(e.path("id").asString()).append(' ').append(e.path("state").asString());
            }
        }
        String equipment = abnormal.isEmpty() ? "모든 설비가 정상 운전 중입니다." : "정상 운전이 아닌 설비: " + abnormal + ".";
        return equipment + " 열린 장애 건 " + data.path("incidents").size() + "건.";
    }
}
