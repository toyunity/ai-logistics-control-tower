package com.example.controltower.application.port.in;

import com.example.controltower.domain.Actor;
import java.util.concurrent.atomic.AtomicInteger;

public final class TurnScope {

    public static final String KEY = "turnScope";

    private final Actor actor;
    private final int maxToolCalls;
    private final AtomicInteger toolCalls = new AtomicInteger(); // 도구가 다른 스레드에서 돌 수 있다

    public TurnScope(Actor actor, int maxToolCalls) {
        this.actor = actor;
        this.maxToolCalls = maxToolCalls;
    }

    public Actor actor() {
        return actor;
    }

    public boolean tryCount() {
        return toolCalls.incrementAndGet() <= maxToolCalls;
    }
}
