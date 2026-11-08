package com.example.controltower.application.service;

import com.example.controltower.domain.ChuteStatus;
import com.example.controltower.domain.CommandSpec;
import com.example.controltower.domain.ConveyorStatus;
import com.example.controltower.domain.RobotStatus;
import com.example.controltower.domain.SorterStatus;
import java.util.TreeMap;

/**
 * 제안과 확정 사이에 이산 상태가 바뀌었는지 비교하는 문자열이다.
 * 수치는 1초마다 변해 매번 불일치가 나므로 넣지 않고 규칙의 기준값 검사에 맡긴다.
 */
final class StateDigest {

    private StateDigest() {}

    static String of(CommandSpec.Restart spec, ConveyorStatus conveyor, SorterStatus sorter) {
        return "conveyor=" + conveyor.state() + "|blocked=" + sorter.blocked();
    }

    /** 규칙을 통과한 뒤에 부른다. 슈트가 없으면 예외다. */
    static String of(CommandSpec.Divert spec, SorterStatus sorter) {
        ChuteStatus from = chute(sorter, spec.fromChute());
        ChuteStatus to = chute(sorter, spec.toChute());
        // 막힘과 만재는 RUNNING 상태 그대로 보고되므로 따로 넣는다.
        return "sorter=" + sorter.state()
                + "|blocked=" + sorter.blocked()
                + "|from=" + from.state() + "/" + from.full()
                + "|to=" + to.state() + "/" + to.full()
                + "|diversions=" + new TreeMap<>(sorter.diversions());
    }

    static String of(CommandSpec.Reassign spec, RobotStatus from, RobotStatus to) {
        return "from=" + from.state() + "|to=" + to.state();
    }

    static String of(CommandSpec.Maintenance spec) {
        return "";
    }

    static ChuteStatus chute(SorterStatus sorter, String id) {
        return sorter.chutes().stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
    }
}
