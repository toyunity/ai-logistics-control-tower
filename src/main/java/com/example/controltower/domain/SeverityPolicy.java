package com.example.controltower.domain;

import java.time.Duration;
import java.util.Objects;

public final class SeverityPolicy {

    private static final Duration SEV1_STOPPED = Duration.ofMinutes(10);
    private static final Duration SEV2_STOPPED = Duration.ofMinutes(3);
    private static final Duration SEV1_CUTOFF = Duration.ofMinutes(30);
    private static final int SEV1_PARCELS = 500;
    private static final int SEV2_PARCELS = 100;

    private SeverityPolicy() {}

    /** @param untilCutoff 출고 마감까지 남은 시간. 마감을 넘기면 음수다. */
    public static Severity grade(Duration stopped, int affectedParcels, Duration untilCutoff) {
        Objects.requireNonNull(stopped);
        Objects.requireNonNull(untilCutoff);
        if (stopped.isNegative() || affectedParcels < 0) {
            throw new IllegalArgumentException("stopped and affected parcels must not be negative");
        }
        boolean stoppedNearCutoff = stopped.isPositive() && untilCutoff.compareTo(SEV1_CUTOFF) <= 0;
        if (stopped.compareTo(SEV1_STOPPED) >= 0 || affectedParcels >= SEV1_PARCELS || stoppedNearCutoff) {
            return Severity.SEV1;
        }
        if (stopped.compareTo(SEV2_STOPPED) >= 0 || affectedParcels >= SEV2_PARCELS) {
            return Severity.SEV2;
        }
        return Severity.SEV3;
    }
}
