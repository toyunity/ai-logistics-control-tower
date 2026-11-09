package com.example.controltower.adapter.out.equipment;

import com.example.controltower.application.port.out.Conveyors;
import com.example.controltower.domain.ConveyorStatus;
import com.example.controltower.domain.FaultClass;
import com.example.controltower.domain.RunState;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** 제조사 A의 PLC 레지스터 맵 [run, alarm, zoneCode]를 번역한다. */
@Component
public class VendorAConveyors implements Conveyors {

    private static final List<String> IDS = List.of("CV-1", "CV-2", "CV-3");
    private static final String PLC = "/sim/vendor-a/plc/{id}";
    private static final int OP_START = 1;

    record Registers(int[] registers) {}

    record Command(UUID cmdId, int op) {}

    private final EquipmentHttp http;

    VendorAConveyors(EquipmentHttp http) {
        this.http = http;
    }

    @Override
    public ConveyorStatus get(String id) {
        return http.read(EquipmentHttp.Group.CONVEYORS, PLC, Registers.class, wire -> translate(id, wire.registers()), id);
    }

    @Override
    public List<ConveyorStatus> all() {
        return IDS.stream().map(this::get).toList();
    }

    @Override
    public void restart(UUID commandId, String id) {
        http.send(EquipmentHttp.Group.CONVEYORS, PLC + "/command", new Command(commandId, OP_START), id);
    }

    private static ConveyorStatus translate(String id, int[] registers) {
        if (registers.length != 3) {
            throw new IllegalArgumentException("expected three registers");
        }
        return new ConveyorStatus(id, zone(registers[2]), state(registers[0]), fault(registers[1]));
    }

    private static RunState state(int run) {
        return switch (run) {
            case 1 -> RunState.RUNNING;
            case 0 -> RunState.STOPPED;
            default -> RunState.UNKNOWN;
        };
    }

    private static FaultClass fault(int alarm) {
        return switch (alarm) {
            case 0 -> FaultClass.NONE;
            case 101 -> FaultClass.DOWNSTREAM_BLOCKED;
            case 205 -> FaultClass.MECHANICAL;
            default -> FaultClass.UNKNOWN;
        };
    }

    // 모르는 구역은 안전 정책의 구역 비교에서 거절된다.
    private static String zone(int code) {
        return switch (code) {
            case 1 -> "Z1";
            case 2 -> "Z2";
            case 3 -> "Z3";
            default -> "UNKNOWN";
        };
    }
}
