package com.example.controltower.application.port.in;

import java.util.List;
import java.util.UUID;

public sealed interface ChatEvent {
    record Meta(UUID conversationId) implements ChatEvent {}
    record Sources(List<Source> items) implements ChatEvent {}
    record Source(String id, String question, Double score) {}
    record Delta(String text) implements ChatEvent {}
    record Done() implements ChatEvent {}
    record Failed(String code, String message) implements ChatEvent {}
}
