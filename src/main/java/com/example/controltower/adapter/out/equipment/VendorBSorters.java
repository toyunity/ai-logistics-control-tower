package com.example.controltower.adapter.out.equipment;

import com.example.controltower.application.port.out.Sorters;
import com.example.controltower.domain.ChuteStatus;
import com.example.controltower.domain.GatewayUnavailableException;
import com.example.controltower.domain.RunState;
import com.example.controltower.domain.SorterStatus;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** 제조사 B의 상태 문자열과 레인 번호를 번역한다. */
@Component
public class VendorBSorters implements Sorters {

    private static final String SORTER_ID = "SR-1";
    private static final String SORTER = "/sim/vendor-b/sorters/" + SORTER_ID;
    private static final Pattern CHUTE_ID = Pattern.compile("CH-\\d\\d");

    record Lane(int laneNo, String grp, int fill, int cap, int rate, String st) {}

    record Reroute(int src, int dst) {}

    record Sorter(String opStatus, List<Lane> lanes, List<Reroute> reroutes) {}

    record RerouteRequest(UUID txn, int src, int dst) {}

    private final EquipmentHttp http;

    VendorBSorters(EquipmentHttp http) {
        this.http = http;
    }

    @Override
    public SorterStatus get() {
        return http.read(EquipmentHttp.Group.SORTER, SORTER, Sorter.class, VendorBSorters::translate);
    }

    @Override
    public void divert(UUID commandId, String fromChute, String toChute) {
        http.send(
                EquipmentHttp.Group.SORTER,
                SORTER + "/reroutes",
                new RerouteRequest(commandId, laneNo(fromChute), laneNo(toChute)));
    }

    private static int laneNo(String chuteId) {
        if (chuteId == null || !CHUTE_ID.matcher(chuteId).matches()) {
            throw new GatewayUnavailableException("chute id out of format", null);
        }
        return Integer.parseInt(chuteId.substring(3));
    }

    private static SorterStatus translate(Sorter sorter) {
        return new SorterStatus(
                SORTER_ID,
                state(sorter.opStatus()),
                !sorter.opStatus().equals("ACTV"),
                sorter.lanes().stream().map(VendorBSorters::chute).toList(),
                sorter.reroutes().stream().collect(Collectors.toUnmodifiableMap(
                        r -> chuteId(r.src()), r -> chuteId(r.dst()))));
    }

    // HOLD를 가동으로 두는 이유: 막힌 슈트를 우회시키는 명령이 차단 중에 가능해야 한다.
    private static RunState state(String opStatus) {
        return switch (opStatus) {
            case "ACTV", "HOLD" -> RunState.RUNNING;
            case "JAM" -> RunState.FAULT;
            default -> RunState.UNKNOWN;
        };
    }

    private static ChuteStatus chute(Lane lane) {
        if (lane.fill() < 0 || lane.fill() > 100) {
            throw new IllegalArgumentException("fill out of range");
        }
        return new ChuteStatus(
                chuteId(lane.laneNo()),
                lane.grp(),
                laneState(lane.st()),
                lane.fill(),
                lane.rate(),
                lane.cap(),
                lane.st().equals("FULL"));
    }

    private static RunState laneState(String st) {
        return switch (st) {
            case "OK", "FULL" -> RunState.RUNNING;
            case "ERR" -> RunState.FAULT;
            default -> RunState.UNKNOWN;
        };
    }

    private static String chuteId(int laneNo) {
        return "CH-%02d".formatted(laneNo);
    }
}
