package com.example.controltower.adapter.in.web;

import com.example.controltower.domain.Role;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.WebSession;
import reactor.core.publisher.Mono;

@RestController
public class SessionController {

    record Login(String accountId) {}

    record Session(String accountId, Role role) {}

    // 로그인 전에 받은 세션 ID를 그대로 쓰면 세션 고정 공격에 열린다. ID를 바꾼 뒤에 계정을 넣는다.
    @PostMapping("/api/session")
    Mono<ResponseEntity<Session>> login(@RequestBody Login login, WebSession session) {
        return SessionActor.account(login.accountId())
                .map(actor -> session.changeSessionId().then(Mono.fromSupplier(() -> {
                    session.getAttributes().put(SessionActor.KEY, actor.accountId());
                    return ResponseEntity.ok(new Session(actor.accountId(), actor.role()));
                })))
                .orElseGet(() -> Mono.just(ResponseEntity.badRequest().build()));
    }

    @DeleteMapping("/api/session")
    Mono<ResponseEntity<Void>> logout(WebSession session) {
        return session.invalidate().thenReturn(ResponseEntity.noContent().build());
    }
}
