package com.example.controltower.application.port.in;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.controltower.domain.Actor;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class TurnScopeTest {

    @Test
    void allowsUpToLimitThenRefuses() {
        var scope = new TurnScope(Actor.ANONYMOUS, 2);

        assertThat(scope.tryCount()).isTrue();
        assertThat(scope.tryCount()).isTrue();
        assertThat(scope.tryCount()).isFalse();
    }

    @Test
    void countsExactlyUnderConcurrency() {
        var scope = new TurnScope(Actor.ANONYMOUS, 100);

        long allowed = IntStream.range(0, 10_000).parallel().filter(i -> scope.tryCount()).count();

        assertThat(allowed).isEqualTo(100);
    }
}
