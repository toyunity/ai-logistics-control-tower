package com.example.controltower.adapter.in.web;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** 애플리케이션 서비스는 JDBC와 설비 HTTP를 블로킹으로 부른다. Netty 이벤트 루프를 막지 않게 boundedElastic에서 돌린다. */
final class Blocking {

    // Scheduler.schedule의 Disposable을 버리므로 구독 취소가 작업을 인터럽트하지 못한다.
    private static final Executor ELASTIC = task -> Schedulers.boundedElastic().schedule(task);

    private Blocking() {}

    /** 읽기용이다. 클라이언트가 끊기면 Reactor가 작업 스레드를 인터럽트한다. */
    static <T> Mono<T> call(Callable<T> work) {
        return Mono.fromCallable(work).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 상태를 바꾸는 호출용이다. 클라이언트가 끊겨도 끝까지 돌고 결과만 버린다.
     * 인터럽트되면 커넥션 획득이 실패해 설비 전송 뒤 기록이 빠질 수 있다.
     * 요청 추적이 이 스레드까지 이어지는지는 ObservabilityIT가 확인한다.
     */
    static <T> Mono<T> runToCompletion(Callable<T> work) {
        return Mono.fromFuture(() -> CompletableFuture.supplyAsync(() -> {
            try {
                return work.call();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }, ELASTIC), true);
    }
}
