package com.example.controltower.adapter.in.web;

import com.example.controltower.application.port.in.IngestEvent;
import com.example.controltower.domain.EquipmentEvent;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** 게이트웨이 수신. 인증이 없다(README 한계). 중복은 저장소가 이벤트 ID로 거르고 200으로 답해 재전송을 멈춘다. */
@RestController
public class EventController {

    private final IngestEvent ingest;

    public EventController(IngestEvent ingest) {
        this.ingest = ingest;
    }

    // 이벤트 ID는 이후 이력에 남으므로 자유 문자열을 받지 않는다. UUID가 들어가는 범위다.
    record EventRequest(
            @NotNull @Pattern(regexp = "[A-Za-z0-9-]{1,64}") String eventId,
            @NotNull String equipmentId,
            @NotNull String code,
            Integer value,
            @NotNull Instant occurredAt) {}

    @PostMapping("/api/events")
    Mono<ResponseEntity<Void>> receive(@Valid @RequestBody EventRequest e) {
        return Blocking.call(() -> {
            try {
                ingest.ingest(new EquipmentEvent(e.eventId(), e.equipmentId(), e.code(), e.value(), e.occurredAt()));
            } catch (IllegalArgumentException rejected) {
                return ResponseEntity.badRequest().<Void>build();
            }
            return ResponseEntity.ok().<Void>build();
        });
    }
}
