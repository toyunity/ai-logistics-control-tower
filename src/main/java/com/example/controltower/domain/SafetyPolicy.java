package com.example.controltower.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/** 검사 순서는 안전 신호 먼저, 설비 상태 다음이다. 이유가 겹치면 안전 사유를 먼저 보인다. */
public final class SafetyPolicy {

    private static final Decision ALLOWED = new Decision.Allowed();

    private final SafetyLimits limits;

    public SafetyPolicy(SafetyLimits limits) {
        this.limits = limits;
    }

    public Decision restart(ConveyorStatus c, SorterStatus s, Optional<SafetyStatus> safety, Instant now) {
        if (safety.isEmpty()) {
            return deny(DenialCode.SAFETY_SIGNAL_UNKNOWN);
        }
        SafetyStatus signal = safety.get();
        if (!signal.zone().equals(c.zone())) {
            return deny(DenialCode.SAFETY_SIGNAL_UNKNOWN);
        }
        // 미래 시각은 시계가 어긋났다는 뜻이므로 같은 기준으로 막는다.
        if (Duration.between(signal.measuredAt(), now).abs().compareTo(limits.signalMaxAge()) > 0) {
            return deny(DenialCode.SAFETY_SIGNAL_STALE);
        }
        if (signal.emergencyStop()) {
            return deny(DenialCode.EMERGENCY_STOP_ACTIVE);
        }
        if (signal.workerPresent()) {
            return deny(DenialCode.WORKER_IN_ZONE);
        }
        if (c.state() == RunState.UNKNOWN || c.fault() == FaultClass.UNKNOWN || s.state() == RunState.UNKNOWN) {
            return deny(DenialCode.STATE_UNKNOWN);
        }
        // 고장 난 컨베이어가 FAULT로 보고돼도 NOT_STOPPED 대신 진짜 이유를 보이려고 먼저 본다.
        if (c.fault() == FaultClass.MECHANICAL) {
            return deny(DenialCode.MECHANICAL_FAULT);
        }
        if (c.state() != RunState.STOPPED) {
            return deny(DenialCode.NOT_STOPPED);
        }
        if (s.blocked()) {
            return deny(DenialCode.SORTER_BLOCKED);
        }
        if (s.state() != RunState.RUNNING) {
            return deny(DenialCode.SORTER_NOT_RUNNING);
        }
        return ALLOWED;
    }

    // 분류기 차단과 원본 만재는 우회의 이유이므로 검사하지 않는다.
    public Decision divert(SorterStatus s, String from, String to) {
        if (from.equals(to)) {
            return deny(DenialCode.SAME_TARGET);
        }
        if (s.state() == RunState.UNKNOWN) {
            return deny(DenialCode.STATE_UNKNOWN);
        }
        if (s.state() != RunState.RUNNING) {
            return deny(DenialCode.SORTER_NOT_RUNNING);
        }
        Optional<ChuteStatus> source = chute(s, from);
        Optional<ChuteStatus> target = chute(s, to);
        if (source.isEmpty() || target.isEmpty()) {
            return deny(DenialCode.NOT_FOUND);
        }
        ChuteStatus src = source.get();
        ChuteStatus dst = target.get();
        if (src.state() == RunState.UNKNOWN) {
            return deny(DenialCode.STATE_UNKNOWN);
        }
        if (dst.state() != RunState.RUNNING) {
            return deny(DenialCode.CHUTE_UNAVAILABLE);
        }
        if (dst.full()) {
            return deny(DenialCode.CHUTE_FULL);
        }
        if (!src.group().equals(dst.group())) {
            return deny(DenialCode.GROUP_MISMATCH);
        }
        if (src.ratePerMin() + dst.ratePerMin() > dst.capacityPerMin()) {
            return deny(DenialCode.OVER_CAPACITY);
        }
        return ALLOWED;
    }

    // 원본 OFFLINE은 막지 않는다. 멈춘 로봇의 작업도 옮길 수 있어야 한다.
    public Decision reassign(RobotStatus from, RobotStatus to) {
        if (from.id().equals(to.id())) {
            return deny(DenialCode.SAME_TARGET);
        }
        if (from.state() == RunState.UNKNOWN) {
            return deny(DenialCode.STATE_UNKNOWN);
        }
        if (from.queuedTasks() <= 0) {
            return deny(DenialCode.NO_TASKS);
        }
        if (to.state() == RunState.OFFLINE || to.state() == RunState.UNKNOWN || to.state() == RunState.FAULT) {
            return deny(DenialCode.ROBOT_OFFLINE);
        }
        if (to.batteryPercent() < limits.minBatteryPercent()) {
            return deny(DenialCode.LOW_BATTERY);
        }
        if (to.queuedTasks() >= to.queueCapacity()) {
            return deny(DenialCode.QUEUE_FULL);
        }
        return ALLOWED;
    }

    private static Optional<ChuteStatus> chute(SorterStatus s, String id) {
        return s.chutes().stream().filter(c -> c.id().equals(id)).findFirst();
    }

    private static Decision deny(DenialCode code) {
        return new Decision.Denied(code);
    }
}
