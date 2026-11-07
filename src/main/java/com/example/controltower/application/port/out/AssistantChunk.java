package com.example.controltower.application.port.out;

import com.example.controltower.application.port.in.ChatEvent.Source;
import java.util.List;

// 어댑터는 내용 없는 업스트림 청크(null, thinking, 빈 텍스트)도 빈 Text로 넘긴다. 유휴 판정이 서비스에 있기 때문이다.
public sealed interface AssistantChunk {
    record Retrieved(List<Source> sources) implements AssistantChunk {}
    record Text(String text) implements AssistantChunk {}
    record Refused() implements AssistantChunk {}
}
