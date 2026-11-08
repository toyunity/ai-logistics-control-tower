package com.example.controltower.application.port.in;

import com.example.controltower.domain.Actor;
import com.example.controltower.domain.PendingCommand;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public final class TurnScope {

    public static final String KEY = "turnScope";

    private final Actor actor;
    private final int maxToolCalls;
    private final AtomicInteger toolCalls = new AtomicInteger(); // 도구가 다른 스레드에서 돌 수 있다
    private final Map<String, PendingCommand> proposed = new LinkedHashMap<>();

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

    /** 같은 종류·대상의 카드는 하나만 남긴다. 재사용된 카드는 한 번, 무효화된 이전 카드는 새 카드로 바뀐다. */
    public synchronized void add(PendingCommand command) {
        proposed.put(command.spec().kind() + ":" + command.spec().targetId(), command);
    }

    public synchronized List<PendingCommand> proposed() {
        return List.copyOf(proposed.values());
    }
}
