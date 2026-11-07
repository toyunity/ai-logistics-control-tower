package com.example.controltower.domain;

import static java.util.Objects.requireNonNull;

/** 모델이 넘긴 ID는 검증 전 값이다. 비었거나 모르는 ID는 제안 단계에서 NOT_FOUND로 막힌다. */
public sealed interface CommandSpec {

    enum Kind {
        RESTART,
        DIVERT,
        REASSIGN,
        MAINTENANCE
    }

    Kind kind();

    /** 같은 대상의 살아 있는 카드를 찾는 키다. 두 설비가 걸리면 원본 쪽이다. */
    String targetId();

    record Restart(String conveyorId) implements CommandSpec {
        public Restart {
            requireNonNull(conveyorId);
        }

        @Override
        public Kind kind() {
            return Kind.RESTART;
        }

        @Override
        public String targetId() {
            return conveyorId;
        }
    }

    record Divert(String fromChute, String toChute) implements CommandSpec {
        public Divert {
            requireNonNull(fromChute);
            requireNonNull(toChute);
        }

        @Override
        public Kind kind() {
            return Kind.DIVERT;
        }

        @Override
        public String targetId() {
            return fromChute;
        }
    }

    record Reassign(String fromRobot, String toRobot) implements CommandSpec {
        public Reassign {
            requireNonNull(fromRobot);
            requireNonNull(toRobot);
        }

        @Override
        public Kind kind() {
            return Kind.REASSIGN;
        }

        @Override
        public String targetId() {
            return fromRobot;
        }
    }

    record Maintenance(String equipmentId, String summary) implements CommandSpec {
        public Maintenance {
            requireNonNull(equipmentId);
            requireNonNull(summary);
        }

        @Override
        public Kind kind() {
            return Kind.MAINTENANCE;
        }

        @Override
        public String targetId() {
            return equipmentId;
        }
    }
}
