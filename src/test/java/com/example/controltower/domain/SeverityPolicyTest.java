package com.example.controltower.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SeverityPolicyTest {

    @ParameterizedTest
    @CsvSource({
        // 정지 시간
        "PT10M,    0,   PT2H,    SEV1",
        "PT9M59S,  0,   PT2H,    SEV2",
        "PT3M,     0,   PT2H,    SEV2",
        "PT2M59S,  0,   PT2H,    SEV3",
        // 영향 물량
        "PT0S,     500, PT2H,    SEV1",
        "PT0S,     499, PT2H,    SEV2",
        "PT0S,     100, PT2H,    SEV2",
        "PT0S,     99,  PT2H,    SEV3",
        // 마감: 정지 중일 때만 적용하고 마감을 넘긴 경우도 임박으로 본다
        "PT1S,     0,   PT30M,   SEV1",
        "PT1S,     0,   PT30M1S, SEV3",
        "PT1S,     0,   PT0S,    SEV1",
        "PT1S,     0,   -PT5M,   SEV1",
        "PT0S,     0,   PT30M,   SEV3",
        "PT0S,     0,   -PT5M,   SEV3",
    })
    void grades(Duration stopped, int affectedParcels, Duration untilCutoff, Severity expected) {
        assertThat(SeverityPolicy.grade(stopped, affectedParcels, untilCutoff)).isEqualTo(expected);
    }

    @Test
    void rejectsNullDurations() {
        assertThatNullPointerException().isThrownBy(() -> SeverityPolicy.grade(null, 0, Duration.ZERO));
        // 앞선 조건으로 등급이 정해져도 빠진 입력을 넘기지 않는다.
        assertThatNullPointerException().isThrownBy(() -> SeverityPolicy.grade(Duration.ofMinutes(10), 0, null));
    }

    @Test
    void rejectsNegativeStoppedOrParcels() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> SeverityPolicy.grade(Duration.ofSeconds(-1), 0, Duration.ofHours(2)));
        assertThatIllegalArgumentException().isThrownBy(() -> SeverityPolicy.grade(Duration.ZERO, -1, Duration.ofHours(2)));
    }
}
