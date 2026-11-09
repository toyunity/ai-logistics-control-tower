package com.example.controltower.adapter.in.web;

import com.example.controltower.application.port.in.CommandResult;
import com.example.controltower.application.port.in.DecideCommand;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.WebSession;
import reactor.core.publisher.Mono;

/** 확정 경로다. AI를 거치지 않고 사람이 누른 버튼만 들어온다. */
@RestController
public class CommandController {

    private final DecideCommand decide;

    public CommandController(DecideCommand decide) {
        this.decide = decide;
    }

    @PostMapping("/api/commands/{id}/confirm")
    Mono<ResponseEntity<ApiResult>> confirm(@PathVariable UUID id, WebSession session) {
        return Blocking.call(() -> reply(decide.confirm(id, SessionActor.of(session))));
    }

    @PostMapping("/api/commands/{id}/reject")
    Mono<ResponseEntity<ApiResult>> reject(@PathVariable UUID id, WebSession session) {
        return Blocking.call(() -> reply(decide.reject(id, SessionActor.of(session))));
    }

    // FORBIDDEN과 NOT_FOUND는 status가 null이다.
    private static ResponseEntity<ApiResult> reply(CommandResult result) {
        HttpStatus code = switch (result.outcome()) {
            case OK -> HttpStatus.OK;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
        };
        String status = result.status() == null ? null : result.status().name();
        return ResponseEntity.status(code).body(new ApiResult(status, result.message()));
    }
}
