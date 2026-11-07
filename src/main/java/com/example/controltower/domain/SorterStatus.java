package com.example.controltower.domain;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** @param diversions 원본 슈트 ID에서 대상 슈트 ID로의 현재 우회 설정 */
public record SorterStatus(
        String id, RunState state, boolean blocked, List<ChuteStatus> chutes, Map<String, String> diversions) {

    public SorterStatus {
        Objects.requireNonNull(state);
    }
}
