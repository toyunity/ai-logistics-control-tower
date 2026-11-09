package com.example.controltower.demo;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 데모의 "말 이해" 부분. 도구 호출만 만든다. 확정은 HTTP로만 들어오므로 실제 모델보다 더 할 수 있는 일이 없다. */
final class DemoIntent {

    record ToolRequest(String name, String argsJson) {}

    // XCH-03처럼 더 긴 토큰 안의 ID는 잡지 않는다.
    private static final String NOT_IN_WORD = "(?<![A-Za-z])";
    private static final Pattern CONVEYOR = Pattern.compile(NOT_IN_WORD + "CV-\\d+", Pattern.CASE_INSENSITIVE);
    private static final Pattern CHUTE = Pattern.compile(NOT_IN_WORD + "CH-\\d+", Pattern.CASE_INSENSITIVE);
    private static final Pattern ROBOT = Pattern.compile(NOT_IN_WORD + "AMR-\\d+", Pattern.CASE_INSENSITIVE);
    private static final Pattern EQUIPMENT = Pattern.compile(NOT_IN_WORD + "(?:CV|CH|SR|SC|AMR)-\\d+", Pattern.CASE_INSENSITIVE);
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
            List<String> pair = fromTo(CHUTE, question, chutes);
            return call("proposeDivert", "{\"fromChute\":\"%s\",\"toChute\":\"%s\"}", pair.get(0), pair.get(1));
        }
        if (question.contains("재배정") && robots.size() >= 2) {
            List<String> pair = fromTo(ROBOT, question, robots);
            return call("proposeReassign", "{\"fromRobot\":\"%s\",\"toRobot\":\"%s\"}", pair.get(0), pair.get(1));
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

    /** "로/으로"가 붙은 ID를 받는 쪽으로 본다. 없으면 어순(앞이 보내는 쪽)을 따른다. */
    private static List<String> fromTo(Pattern pattern, String text, List<String> ids) {
        Matcher to = Pattern.compile("(" + pattern.pattern() + ")(?:으로|로)", Pattern.CASE_INSENSITIVE).matcher(text);
        if (to.find()) {
            String target = to.group(1).toUpperCase(Locale.ROOT);
            Optional<String> from = ids.stream().filter(id -> !id.equals(target)).findFirst();
            if (from.isPresent()) {
                return List.of(from.get(), target);
            }
        }
        return ids.subList(0, 2);
    }

    private static Optional<ToolRequest> call(String name, String template, Object... args) {
        return Optional.of(new ToolRequest(name, template.formatted(args)));
    }
}
