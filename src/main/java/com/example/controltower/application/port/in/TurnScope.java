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

    /** 한도를 넘으면 false다. 한도의 두 배를 넘으면 모델이 한도 결과를 무시하는 것으로 보고 턴을 끝낸다. */
    public boolean tryCount() {
        int n = toolCalls.incrementAndGet();
        if (n > maxToolCalls * 2) {
            throw new BudgetExhausted(); // 한도 결과를 돌려줘도 모델 호출은 계속 과금된다
        }
        return n <= maxToolCalls;
    }

    /** 같은 종류·대상의 카드는 하나만 남긴다. 재사용된 카드는 한 번, 무효화된 이전 카드는 새 카드로 바뀐다. */
    public synchronized void add(PendingCommand command) {
        proposed.put(command.spec().kind() + ":" + command.spec().targetId(), command);
    }

    public synchronized List<PendingCommand> proposed() {
        return List.copyOf(proposed.values());
    }

    /** 도구 콜백 바깥에서 던져 도구 예외 처리기에 잡히지 않고 스트림을 error로 끝낸다. */
    public static final class BudgetExhausted extends RuntimeException {

        public BudgetExhausted() {
            super("tool call budget exhausted", null, false, false);
        }
    }
}
