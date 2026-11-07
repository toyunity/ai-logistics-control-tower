package com.example.controltower.simulator;

import java.time.Instant;
import java.util.List;

/** 불변 사본이다. HTTP 스레드가 락 밖에서 직렬화한다. 필드 이름은 제조사 JSON 키와 같다. */
public record SimSnapshot(
        List<Plc> conveyors,
        String opStatus,
        List<Lane> lanes,
        List<Reroute> reroutes,
        List<Robot> robots,
        List<Safety> safety,
        int backlog,
        boolean safetyOnline) {

    public record Plc(String id, int run, int alarm, int zoneCode) {}

    public record Lane(int laneNo, String grp, int fill, int cap, int rate, String st) {}

    public record Reroute(int src, int dst) {}

    /** @param soc 정수 퍼센트. 소수 표현으로 내보내는 일은 HTTP 계층이 맡는다. */
    public record Robot(String name, String mode, int soc, int jobs, int maxJobs) {}

    public record Safety(String zone, int estop, int presence, Instant ts) {}
}
