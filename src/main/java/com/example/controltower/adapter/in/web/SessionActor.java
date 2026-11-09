package com.example.controltower.adapter.in.web;

import com.example.controltower.domain.Actor;
import com.example.controltower.domain.Role;
import java.util.Map;
import java.util.Optional;
import org.springframework.web.server.WebSession;

/** 샘플 계정이다. 실제 인증이 들어올 자리다. 세션에는 계정 ID만 두고 역할은 요청마다 이 표에서 다시 읽는다. */
final class SessionActor {

    static final String KEY = "accountId";

    private static final Map<String, Role> ACCOUNTS = Map.of("operator", Role.OPERATOR, "viewer", Role.VIEWER);

    private SessionActor() {}

    static Optional<Actor> account(String accountId) {
        return Optional.ofNullable(accountId).map(ACCOUNTS::get).map(role -> new Actor(accountId, role));
    }

    /** 속성을 읽기만 하면 세션이 시작되지 않아 저장도 쿠키 발급도 없다. 비로그인 조회와 대화가 세션을 쌓지 않게 하려는 것이다. */
    static Actor of(WebSession session) {
        return session.getAttribute(KEY) instanceof String id ? account(id).orElse(Actor.ANONYMOUS) : Actor.ANONYMOUS;
    }
}
