package com.example.controltower.application.port.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        assertThat(scope.tryCount()).isFalse();
        assertThatThrownBy(scope::tryCount).isInstanceOf(TurnScope.BudgetExhausted.class);
    }

    @Test
    void countsExactlyUnderConcurrency() {
        var scope = new TurnScope(Actor.ANONYMOUS, 100);

        long allowed = IntStream.range(0, 200).parallel().filter(i -> scope.tryCount()).count();

        assertThat(allowed).isEqualTo(100);
    }
}
