package com.example.controltower.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.otlp.OtlpTracingAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Jaeger로 가는 실제 OTLP 내보내기도 감싸지는지 본다. 엔드포인트가 없으면 내보내기 자체가 없다. */
class RedactingSpanExportTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OtlpTracingAutoConfiguration.class))
            .withBean(RedactingSpanExport.class);

    @Test
    void otlpExporterIsWrapped_whenEndpointIsSet() {
        runner.withPropertyValues("management.opentelemetry.tracing.export.otlp.endpoint=http://127.0.0.1:1/v1/traces")
                .run(context -> {
                    assertThat(context.getBeansOfType(SpanExporter.class)).hasSize(1);
                    SpanExporter exporter = context.getBean(SpanExporter.class);
                    assertThat(exporter).isInstanceOf(RedactingSpanExport.Redacting.class);
                    // 내보내기 모듈은 런타임 의존성이라 이름으로 비교한다.
                    assertThat(((RedactingSpanExport.Redacting) exporter).delegate().getClass().getName())
                            .isEqualTo("io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter");
                });
    }

    @Test
    void noExporterWithoutEndpoint() {
        runner.run(context -> assertThat(context.getBeansOfType(SpanExporter.class)).isEmpty());
    }
}
