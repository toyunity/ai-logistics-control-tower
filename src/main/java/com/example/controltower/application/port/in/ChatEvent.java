package com.example.controltower.application.port.in;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public sealed interface ChatEvent {
    record Meta(UUID conversationId) implements ChatEvent {}
    record Sources(List<Source> items) implements ChatEvent {}
    record Source(String id, String question, Double score) {}
    record Delta(String text) implements ChatEvent {}
    /** 확인 카드. 서버가 저장한 대기 명령 값이다. 정비 summary만 모델이 쓴 문장이며 표시용이다. */
    record Action(UUID commandId, String kind, String targetId, String summary, String impact, Instant expiresAt)
            implements ChatEvent {}
    record Done() implements ChatEvent {}
    record Failed(String code, String message) implements ChatEvent {}
}
