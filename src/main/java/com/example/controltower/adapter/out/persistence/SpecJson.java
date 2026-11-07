package com.example.controltower.adapter.out.persistence;

import com.example.controltower.domain.CommandSpec;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** 저장 형식이 앱의 매퍼 설정을 따라 바뀌지 않도록 전용 매퍼를 쓴다. */
final class SpecJson {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private SpecJson() {}

    static String write(CommandSpec spec) {
        ObjectNode json = JSON.createObjectNode().put("kind", spec.kind().name());
        switch (spec) {
            case CommandSpec.Restart s -> json.put("conveyorId", s.conveyorId());
            case CommandSpec.Divert s -> json.put("fromChute", s.fromChute()).put("toChute", s.toChute());
            case CommandSpec.Reassign s -> json.put("fromRobot", s.fromRobot()).put("toRobot", s.toRobot());
            case CommandSpec.Maintenance s -> json.put("equipmentId", s.equipmentId()).put("summary", s.summary());
        }
        return JSON.writeValueAsString(json);
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
