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
    private Runnable afterSend = () -> {};
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

    /** 전송이 기록되고 적용된 뒤에 실행한다. 던지면 응답을 잃은 전송, 상태를 되돌리면 아직 반영되지 않은 전송을 흉내 낸다. */
    public void afterSend(Runnable afterSend) {
        this.afterSend = afterSend;
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
        afterSend.run();
    }

    private void failIfDown() {
        if (failing) {
            throw new GatewayUnavailableException("robot gateway down", null);
        }
    }
}
