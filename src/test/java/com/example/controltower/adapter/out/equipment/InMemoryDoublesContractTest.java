package com.example.controltower.adapter.out.equipment;

import com.example.controltower.application.port.out.Conveyors;
import com.example.controltower.application.port.out.RobotFleet;
import com.example.controltower.application.port.out.SafetySignals;
import com.example.controltower.application.port.out.Sorters;
import com.example.controltower.domain.CommandOutcomeUnknownException;
import com.example.controltower.domain.ConveyorStatus;
import com.example.controltower.domain.FaultClass;
import com.example.controltower.domain.RunState;
import com.example.controltower.domain.SafetyStatus;
import com.example.controltower.domain.SorterStatus;
import com.example.controltower.support.InMemoryConveyors;
import com.example.controltower.support.InMemoryRobotFleet;
import com.example.controltower.support.InMemorySafetySignals;
import com.example.controltower.support.InMemorySorters;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 메모리 대역이 어댑터와 같은 약속을 지키는지 본다. 애플리케이션 테스트가 이 대역을 믿고 있다. */
class InMemoryDoublesContractTest {

    private static RuntimeException lost() {
        return new CommandOutcomeUnknownException("response lost", null);
    }

    static class ConveyorsDouble extends ConveyorsContract {
        private final InMemoryConveyors conveyors = new InMemoryConveyors(
                new ConveyorStatus("CV-1", "Z1", RunState.RUNNING, FaultClass.NONE),
                new ConveyorStatus("CV-2", "Z1", RunState.RUNNING, FaultClass.NONE),
                new ConveyorStatus("CV-3", "Z1", RunState.RUNNING, FaultClass.NONE));

        @Override
        Conveyors subject() {
            return conveyors;
        }

        @Override
        void givenStoppedByDownstreamBlock(String id) {
            conveyors.set(new ConveyorStatus(id, "Z1", RunState.STOPPED, FaultClass.DOWNSTREAM_BLOCKED));
        }

        @Override
        void goDown() {
            conveyors.setFailing(true);
        }

        @Override
        void loseSendResponses() {
            conveyors.afterSend(() -> {
                throw lost();
            });
        }

        @Override
        List<UUID> sentCommandIds() {
            return conveyors.commandIds();
        }
    }

    static class SortersDouble extends SortersContract {
        private final InMemorySorters sorters = new InMemorySorters(
                new SorterStatus("SR-1", RunState.RUNNING, false, List.of(CH_03, CH_04), Map.of()));

        @Override
        Sorters subject() {
            return sorters;
        }

        @Override
        void goDown() {
            sorters.setFailing(true);
        }

        @Override
        void loseSendResponses() {
            sorters.afterSend(() -> {
                throw lost();
            });
        }

        @Override
        List<UUID> sentCommandIds() {
            return sorters.commandIds();
        }
    }

    static class RobotFleetDouble extends RobotFleetContract {
        private final InMemoryRobotFleet fleet = new InMemoryRobotFleet(AMR_01, AMR_02);

        @Override
        RobotFleet subject() {
            return fleet;
        }

        @Override
        void goDown() {
            fleet.setFailing(true);
        }

        @Override
        void loseSendResponses() {
            fleet.afterSend(() -> {
                throw lost();
            });
        }

        @Override
        List<UUID> sentCommandIds() {
            return fleet.commandIds();
        }
    }

    static class SafetySignalsDouble extends SafetySignalsContract {
        private final InMemorySafetySignals signals = new InMemorySafetySignals(Clock.systemUTC());

        @Override
        SafetySignals subject() {
            return signals;
        }

        @Override
        void givenSignal(String zone, boolean emergencyStop, boolean workerPresent, Instant measuredAt) {
            signals.set(new SafetyStatus(zone, emergencyStop, workerPresent, measuredAt));
        }

        @Override
        void goDown() {
            signals.setFailing(true);
        }
    }
}
