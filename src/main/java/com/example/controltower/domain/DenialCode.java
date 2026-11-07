package com.example.controltower.domain;

public enum DenialCode {
    NOT_PERMITTED("이 명령을 실행할 권한이 없습니다."),
    NOT_FOUND("대상 설비를 찾을 수 없습니다."),
    SAFETY_SIGNAL_UNKNOWN("안전 신호를 확인할 수 없어 명령을 막았습니다."),
    SAFETY_SIGNAL_STALE("안전 신호가 오래되어 명령을 막았습니다."),
    EMERGENCY_STOP_ACTIVE("구역 비상정지가 해제되지 않았습니다."),
    WORKER_IN_ZONE("구역에 작업자가 있습니다."),
    STATE_UNKNOWN("설비 상태를 알 수 없어 명령을 막았습니다."),
    NOT_STOPPED("설비가 정지 상태가 아닙니다."),
    SORTER_BLOCKED("분류기가 차단 상태입니다. 만재 슈트를 먼저 우회해야 합니다."),
    MECHANICAL_FAULT("기계 고장으로 재가동할 수 없습니다. 정비가 필요합니다."),
    SORTER_NOT_RUNNING("분류기가 가동 중이 아닙니다."),
    CHUTE_UNAVAILABLE("대상 슈트가 정상 상태가 아닙니다."),
    CHUTE_FULL("대상 슈트가 만재입니다."),
    GROUP_MISMATCH("두 슈트의 목적지 그룹이 다릅니다."),
    OVER_CAPACITY("우회하면 대상 슈트의 처리 용량을 넘습니다."),
    NO_TASKS("원본 로봇에 재배정할 작업이 없습니다."),
    ROBOT_OFFLINE("대상 로봇을 사용할 수 없습니다."),
    LOW_BATTERY("대상 로봇의 배터리가 부족합니다."),
    QUEUE_FULL("대상 로봇의 작업 큐가 가득 찼습니다."),
    MAINTENANCE_ALREADY_OPEN("이 설비에 열린 정비 요청이 이미 있습니다.");

    private final String message;

    DenialCode(String message) {
        this.message = message;
    }

    public String message() {
        return message;
    }
}
