package com.example.controltower.demo;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;

/** 데모의 "말 이해" 부분. 도구 호출만 만든다. 확정은 HTTP로만 들어오므로 실제 모델보다 더 할 수 있는 일이 없다. */
final class DemoIntent {

    record ToolRequest(String name, String argsJson) {}

    private static final Pattern CONVEYOR = Pattern.compile("CV-\\d+", Pattern.CASE_INSENSITIVE);
    private static final Pattern CHUTE = Pattern.compile("CH-\\d+", Pattern.CASE_INSENSITIVE);
    private static final Pattern ROBOT = Pattern.compile("AMR-\\d+", Pattern.CASE_INSENSITIVE);
    private static final Pattern EQUIPMENT = Pattern.compile("(?:CV|CH|SR|SC|AMR)-\\d+", Pattern.CASE_INSENSITIVE);
    private static final Pattern WHY = Pattern.compile("왜 멈|원인");
    private static final Pattern STATUS = Pattern.compile("상황|현황|현장");
    // 사용자 문장을 카드에 옮기지 않으려고 고정 문구를 쓴다.
    private static final String MAINTENANCE_SUMMARY = "운영자가 대화에서 정비 점검을 요청했습니다.";

    private DemoIntent() {}

    // ID는 정규식으로만 뽑으므로 JSON에 그대로 넣어도 따옴표가 섞이지 않는다.
    static Optional<ToolRequest> detect(String question) {
        List<String> chutes = ids(CHUTE, question);
        List<String> robots = ids(ROBOT, question);
        List<String> conveyors = ids(CONVEYOR, question);
        List<String> equipment = ids(EQUIPMENT, question);
        if (question.contains("우회") && chutes.size() >= 2) {
            return call("proposeDivert", "{\"fromChute\":\"%s\",\"toChute\":\"%s\"}", chutes.get(0), chutes.get(1));
        }
        if (question.contains("재배정") && robots.size() >= 2) {
            return call("proposeReassign", "{\"fromRobot\":\"%s\",\"toRobot\":\"%s\"}", robots.get(0), robots.get(1));
        }
        if (question.contains("재가동") && !conveyors.isEmpty()) {
            return call("proposeRestart", "{\"conveyorId\":\"%s\"}", conveyors.getFirst());
        }
        if (question.contains("정비") && !equipment.isEmpty()) {
            return call("proposeMaintenance", "{\"equipmentId\":\"%s\",\"summary\":\"%s\"}",
                    equipment.getFirst(), MAINTENANCE_SUMMARY);
        }
        if (WHY.matcher(question).find() && !equipment.isEmpty()) {
            return call("diagnose", "{\"equipmentId\":\"%s\"}", equipment.getFirst());
        }
        if (STATUS.matcher(question).find()) {
            return call("getPlantStatus", "{}");
        }
        return Optional.empty();
    }

    private static List<String> ids(Pattern pattern, String text) {
        return pattern.matcher(text).results().map(MatchResult::group).map(id -> id.toUpperCase(Locale.ROOT)).toList();
    }

    private static Optional<ToolRequest> call(String name, String template, Object... args) {
        return Optional.of(new ToolRequest(name, template.formatted(args)));
    }
}
