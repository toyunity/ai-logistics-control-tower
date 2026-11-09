package com.example.controltower.adapter.in.web;

import java.util.concurrent.Callable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** 애플리케이션 서비스는 JDBC와 설비 HTTP를 블로킹으로 부른다. Netty 이벤트 루프를 막지 않게 boundedElastic에서 돌린다. */
final class Blocking {

    private Blocking() {}

    static <T> Mono<T> call(Callable<T> work) {
        return Mono.fromCallable(work).subscribeOn(Schedulers.boundedElastic());
    }
}
