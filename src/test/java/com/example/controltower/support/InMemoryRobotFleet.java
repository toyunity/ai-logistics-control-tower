package com.example.controltower.support;

import com.example.controltower.application.port.out.RobotFleet;
import com.example.controltower.domain.GatewayUnavailableException;
import com.example.controltower.domain.RobotStatus;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class InMemoryRobotFleet implements RobotFleet {

    private final Map<String, RobotStatus> robots = new LinkedHashMap<>();
    private final List<UUID> commandIds = new ArrayList<>();
    private boolean failing;
    private int reads;

    public InMemoryRobotFleet(RobotStatus... initial) {
        for (RobotStatus r : initial) {
            set(r);
        }
    }

    public void set(RobotStatus status) {
        robots.put(status.id(), status);
    }

    public void setFailing(boolean failing) {
        this.failing = failing;
    }

    public int reads() {
        return reads;
    }

    public List<UUID> commandIds() {
        return commandIds;
    }

    @Override
    public List<RobotStatus> all() {
        reads++;
        failIfDown();
        return List.copyOf(robots.values());
    }

    @Override
    public void reassign(UUID commandId, String fromRobot, String toRobot) {
        failIfDown();
        commandIds.add(commandId);
        RobotStatus from = robots.get(fromRobot);
        RobotStatus to = robots.get(toRobot);
        set(new RobotStatus(from.id(), from.state(), from.batteryPercent(), 0, from.queueCapacity()));
        set(new RobotStatus(
                to.id(), to.state(), to.batteryPercent(), to.queuedTasks() + from.queuedTasks(), to.queueCapacity()));
    }

    private void failIfDown() {
        if (failing) {
            throw new GatewayUnavailableException("robot gateway down", null);
        }
    }
}
