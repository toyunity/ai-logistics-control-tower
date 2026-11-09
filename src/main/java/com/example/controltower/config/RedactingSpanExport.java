package com.example.controltower.config;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.DelegatingSpanData;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.data.StatusData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.Collection;
import java.util.List;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;

/**
 * 추적 처리기는 관측의 예외 문구를 상태 설명에, 문구와 원인까지의 스택을 예외 이벤트에 넣는다.
 * Spring AI와 WebFlux 관측의 예외 문구에는 사용자 입력, 모델 출력, 도구 인자가 들 수 있고 끄는 설정이 없다.
 * 그래서 모든 SpanExporter를 감싸 내보내기 직전에 예외는 타입만, 상태는 코드만 남긴다.
 */
@Component
class RedactingSpanExport implements BeanPostProcessor {

    private static final AttributeKey<String> EXCEPTION_TYPE = AttributeKey.stringKey("exception.type");

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        return bean instanceof SpanExporter exporter && !(bean instanceof Redacting)
                ? new Redacting(exporter)
                : bean;
    }

    record Redacting(SpanExporter delegate) implements SpanExporter {

        @Override
        public CompletableResultCode export(Collection<SpanData> spans) {
            return delegate.export(spans.stream().<SpanData>map(Redacted::new).toList());
        }

        @Override
        public CompletableResultCode flush() {
            return delegate.flush();
        }

        @Override
        public CompletableResultCode shutdown() {
            return delegate.shutdown();
        }
    }

    private static final class Redacted extends DelegatingSpanData {

        Redacted(SpanData delegate) {
            super(delegate);
        }

        @Override
        public StatusData getStatus() {
            return StatusData.create(super.getStatus().getStatusCode(), "");
        }

        // 이벤트 속성에는 예외 타입만 남긴다. 이 앱의 다른 이벤트는 속성이 없다(내용 기록 옵션을 껐다).
        @Override
        public List<EventData> getEvents() {
            return super.getEvents().stream()
                    .map(e -> {
                        String type = e.getAttributes().get(EXCEPTION_TYPE);
                        Attributes kept = type == null ? Attributes.empty() : Attributes.of(EXCEPTION_TYPE, type);
                        return EventData.create(e.getEpochNanos(), e.getName(), kept);
                    })
                    .toList();
        }
    }
}
