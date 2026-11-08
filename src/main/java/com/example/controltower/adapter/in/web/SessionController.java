package com.example.controltower.adapter.in.web;

import com.example.controltower.domain.Actor;
import com.example.controltower.domain.Role;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SessionController {

    record Login(String accountId) {}

    record Session(String accountId, Role role) {}

    @PostMapping("/api/session")
    ResponseEntity<Session> login(@RequestBody Login login, HttpServletRequest request) {
        return SessionActor.account(login.accountId())
                .map(actor -> {
                    start(request).setAttribute(SessionActor.KEY, actor.accountId());
                    return ResponseEntity.ok(new Session(actor.accountId(), actor.role()));
                })
                .orElseGet(() -> ResponseEntity.badRequest().build());
    }

    @DeleteMapping("/api/session")
    ResponseEntity<Void> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        return ResponseEntity.noContent().build();
    }

    // 로그인 전에 받은 세션 ID를 그대로 쓰면 세션 고정 공격에 열린다.
    private static HttpSession start(HttpServletRequest request) {
        HttpSession session = request.getSession(true);
        request.changeSessionId();
        return session;
    }
}
