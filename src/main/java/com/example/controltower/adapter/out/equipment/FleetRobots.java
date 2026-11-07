package com.example.controltower.adapter.out.equipment;

import com.example.controltower.application.port.out.RobotFleet;
import com.example.controltower.domain.RobotStatus;
import com.example.controltower.domain.RunState;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** 로봇 관제 시스템의 모드 문자열과 소수 배터리 값을 번역한다. */
@Component
public class FleetRobots implements RobotFleet {

    record Robot(String name, String mode, double soc, int jobs, int maxJobs) {}

    record Reassign(UUID cmdId, String from, String to) {}

    private final EquipmentHttp http;

    FleetRobots(EquipmentHttp http) {
        this.http = http;
    }

    @Override
    public List<RobotStatus> all() {
        return http.read(
                "/sim/fleet/robots",
                Robot[].class,
                robots -> Arrays.stream(robots).map(FleetRobots::translate).toList());
    }

    @Override
    public void reassign(UUID commandId, String fromRobot, String toRobot) {
        http.send("/sim/fleet/reassign", new Reassign(commandId, fromRobot, toRobot));
    }

    private static RobotStatus translate(Robot robot) {
        return new RobotStatus(
                robot.name(), state(robot.mode()), (int) Math.round(robot.soc() * 100), robot.jobs(), robot.maxJobs());
    }

    private static RunState state(String mode) {
        return switch (mode) {
            case "AUTO" -> RunState.RUNNING;
            case "CHARGING" -> RunState.DEGRADED;
            case "LOST" -> RunState.OFFLINE;
            default -> RunState.UNKNOWN;
        };
    }
}
