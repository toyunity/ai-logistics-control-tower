package com.example.controltower.application.port.out;

import com.example.controltower.application.port.in.ChatEvent.Source;
import java.util.List;

public sealed interface AssistantChunk {
    record Retrieved(List<Source> sources) implements AssistantChunk {}
    record Text(String text) implements AssistantChunk {}
    record Refused() implements AssistantChunk {}
}
