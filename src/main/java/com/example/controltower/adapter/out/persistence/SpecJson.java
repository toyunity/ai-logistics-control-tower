package com.example.controltower.adapter.out.persistence;

import com.example.controltower.domain.CommandSpec;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 저장 형식이 앱의 매퍼 설정을 따라 바뀌지 않도록 전용 매퍼를 쓴다. */
final class SpecJson {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private SpecJson() {}

    static String write(CommandSpec spec) {
        return JSON.writeValueAsString(switch (spec) {
            case CommandSpec.Restart s -> Map.of("kind", s.kind().name(), "conveyorId", s.conveyorId());
            case CommandSpec.Divert s -> Map.of("kind", s.kind().name(), "fromChute", s.fromChute(), "toChute", s.toChute());
            case CommandSpec.Reassign s -> Map.of("kind", s.kind().name(), "fromRobot", s.fromRobot(), "toRobot", s.toRobot());
            case CommandSpec.Maintenance s -> Map.of("kind", s.kind().name(), "equipmentId", s.equipmentId(), "summary", s.summary());
        });
    }

    static CommandSpec read(String json) {
        JsonNode node = JSON.readTree(json);
        return switch (node.path("kind").asString("")) {
            case "RESTART" -> new CommandSpec.Restart(field(node, "conveyorId"));
            case "DIVERT" -> new CommandSpec.Divert(field(node, "fromChute"), field(node, "toChute"));
            case "REASSIGN" -> new CommandSpec.Reassign(field(node, "fromRobot"), field(node, "toRobot"));
            case "MAINTENANCE" -> new CommandSpec.Maintenance(field(node, "equipmentId"), field(node, "summary"));
            default -> throw new IllegalStateException("unknown command spec kind");
        };
    }

    private static String field(JsonNode node, String name) {
        return node.required(name).asString();
    }
}
