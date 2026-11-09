package com.example.controltower;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.controltower.application.port.in.CommandResult;
import com.example.controltower.application.port.in.DecideCommand;
import com.example.controltower.application.port.in.PlantStatus;
import com.example.controltower.application.port.in.ProposalResult;
import com.example.controltower.application.port.in.ProposeCommand;
import com.example.controltower.domain.Actor;
import com.example.controltower.domain.CommandSpec;
import com.example.controltower.domain.CommandStatus;
import com.example.controltower.domain.DenialCode;
import com.example.controltower.domain.Role;
import com.example.controltower.support.FakeAiConfig;
import com.example.controltower.support.FakeChatModel;
import com.example.controltower.support.TestInfraConfig;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 추적 내보내기를 켠 채로(테스트 기본값은 끔) 관측 이름과 태그, 한 추적으로 이어지는지, 사용자 입력이 새지 않는지 본다.
 * 메트릭 내보내기 억제도 풀어 OTLP 메트릭 레지스트리가 애플리케이션 설정만으로 꺼져 있는지 확인한다.
 */
@SpringBootTest(
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {
            "management.tracing.sampling.probability=1.0",
            "management.opentelemetry.tracing.export.schedule-delay=100ms",
            "management.defaults.metrics.export.enabled=true"
        })
@AutoConfigureTracing
@Import({TestInfraConfig.class, FakeAiConfig.class, ObservabilityIT.Spans.class})
@ExtendWith(OutputCaptureExtension.class)
class ObservabilityIT {

    private static final String MARKER = "LEAKMARK7F3A";
    private static final Actor OPERATOR = new Actor("operator", Role.OPERATOR);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<String> GROUPS = Set.of("conveyors", "sorter", "robots", "safety-plc");

    // 애플리케이션이 SpanExporter 빈을 감싸므로 타입으로 주입받지 않고 인스턴스를 직접 쥔다.
    private static final InMemorySpanExporter spans = InMemorySpanExporter.create();

    @TestConfiguration(proxyBeanMethods = false)
    static class Spans {

        @Bean
        InMemorySpanExporter inMemorySpanExporter() {
            return spans;
        }
    }

    @LocalServerPort int port;
    @Autowired ApplicationContext context;
    @Autowired MeterRegistry meters;
    @Autowired SdkTracerProvider tracerProvider;
    @Autowired FakeChatModel model;
    @Autowired ProposeCommand propose;
    @Autowired DecideCommand decide;
    @Autowired PlantStatus status;

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .cookieHandler(new CookieManager())
            .build();

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
                BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String json) throws Exception {
        return http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .build(),
                BodyHandlers.ofString());
    }

    @Test
    void actuatorExposesOnlyHealthAndMetrics_withoutHealthDetails() throws Exception {
        HttpResponse<String> index = get("/actuator");
        assertThat(index.statusCode()).isEqualTo(200);
        Set<String> links = new HashSet<>(JSON.readTree(index.body()).get("_links").propertyNames());
        assertThat(links).containsExactlyInAnyOrder("self", "health", "health-path", "metrics",
                "metrics-requiredMetricName");

        HttpResponse<String> health = get("/actuator/health");
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(health.body()).propertyNames()).doesNotContain("components", "details");

        assertThat(get("/actuator/metrics").statusCode()).isEqualTo(200);
        for (String hidden : List.of("env", "configprops", "beans", "circuitbreakerevents", "circuitbreakers",
                "loggers", "mappings", "threaddump", "heapdump", "prometheus", "info", "health/db")) {
            assertThat(get("/actuator/" + hidden).statusCode()).as(hidden).isEqualTo(404);
        }
    }

    @Test
    void breakerStateIsAMicrometerGauge() {
        for (String group : GROUPS) {
            assertThat(meters.find("resilience4j.circuitbreaker.state").tag("name", group).gauges())
                    .as(group)
                    .isNotEmpty();
        }
    }

    // 엔드포인트를 설정하지 않으면 추적·메트릭·로그 모두 밖으로 보내는 구성 요소가 없다.
    @Test
    void nothingIsExportedWithoutAnEndpoint() {
        for (String type : List.of(
                "io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter",
                "io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter",
                "io.opentelemetry.exporter.otlp.http.logs.OtlpHttpLogRecordExporter",
                "io.opentelemetry.exporter.otlp.logs.OtlpGrpcLogRecordExporter",
                "io.micrometer.registry.otlp.OtlpMeterRegistry")) {
            assertThat(beanNamesOf(type)).as(type).isEmpty();
        }
        // 애플리케이션이 SpanExporter 빈을 감싸 실제 타입으로는 찾지 못하므로 이름으로도 본다.
        assertThat(context.getBeansOfType(io.opentelemetry.sdk.trace.export.SpanExporter.class).keySet())
                .containsExactly("inMemorySpanExporter");
    }

    private String[] beanNamesOf(String className) {
        try {
            return context.getBeanNamesForType(Class.forName(className));
        } catch (ClassNotFoundException e) {
            return new String[0];
        }
    }

    @Test
    void commandAndEquipmentObservationsCarryOnlyEnumTags() {
        ProposalResult proposed = propose.propose(new CommandSpec.Maintenance("CV-3", MARKER + " 요약"), OPERATOR);
        if (proposed instanceof ProposalResult.Proposed(var card)) {
            decide.reject(card.id(), OPERATOR);
        }
        decide.confirm(UUID.randomUUID(), OPERATOR);
        propose.propose(new CommandSpec.Restart("CV-3"), OPERATOR);
        status.snapshot();

        assertOnlyAllowedTags("controltower.command.propose", Map.of(
                "kind", names(CommandSpec.Kind.values()),
                "outcome", union(Set.of("PROPOSED", "ERROR"), names(DenialCode.values()))));
        assertOnlyAllowedTags("controltower.command.decide", Map.of(
                "kind", Set.of("CONFIRM", "REJECT"),
                "outcome", union(Set.of("ERROR"), names(CommandResult.Outcome.values())),
                "status", union(Set.of("NONE"), names(CommandStatus.values()))));
        assertOnlyAllowedTags("controltower.equipment.call", Map.of(
                "group", GROUPS,
                "operation", Set.of("read", "send"),
                "outcome", Set.of("OK", "UNAVAILABLE", "OUTCOME_UNKNOWN", "CIRCUIT_OPEN")));
        assertThat(meters.find("controltower.command.decide").tag("kind", "REJECT").timers()).isNotEmpty();
        assertThat(meters.find("controltower.command.decide").tag("status", "NONE").timers()).isNotEmpty();
        assertNoMeterTagContains(MARKER);
    }

    private void assertOnlyAllowedTags(String name, Map<String, Set<String>> allowed) {
        var found = meters.find(name).meters();
        assertThat(found).as(name).isNotEmpty();
        for (Meter meter : found) {
            for (Tag tag : meter.getId().getTags()) {
                // 관측 메트릭 처리기가 붙이는 태그다. 예외를 관측에 넘기지 않으므로 항상 none이어야 한다.
                if (tag.getKey().equals("error")) {
                    assertThat(tag.getValue()).isEqualTo("none");
                    continue;
                }
                assertThat(allowed).as(name + " tag key").containsKey(tag.getKey());
                assertThat(allowed.get(tag.getKey())).as(name + "." + tag.getKey()).contains(tag.getValue());
            }
        }
    }

    private void assertNoMeterTagContains(String marker) {
        for (Meter meter : meters.getMeters()) {
            assertThat(meter.getId().getName()).doesNotContain(marker);
            for (Tag tag : meter.getId().getTags()) {
                assertThat(tag.getValue()).as(meter.getId().getName()).doesNotContain(marker);
            }
        }
    }

    @Test
    void chatTurnThatProposesIsOneTrace_andUserTextNeverReachesTelemetry(CapturedOutput output) throws Exception {
        assertThat(post("/api/session", "{\"accountId\":\"operator\"}").statusCode()).isEqualTo(200);
        model.reset();
        spans.reset();

        // 자유 문자열 인자(정비 요약)에 표식을 넣는다.
        model.enqueueStream(Flux.just(FakeChatModel.text("정비를 제안합니다 "), FakeChatModel.toolCall("c1",
                "proposeMaintenance", "{\"equipmentId\":\"CV-3\",\"summary\":\"" + MARKER + " 벨트 소음\"}")));
        model.enqueueStream(Flux.just(FakeChatModel.text("카드를 확인하세요"), FakeChatModel.finish("end_turn")));
        String sse = post("/api/chat/stream", "{\"message\":\"CV-3 정비 올려 " + MARKER + "\"}").body();
        assertThat(sse).contains("event:action").contains("event:done");

        // 형식이 어긋난 인자: Jackson 예외 문구에 인자 원문이 들어간다.
        model.enqueueStream(Flux.just(FakeChatModel.toolCall("c2", "proposeMaintenance",
                "{\"equipmentId\":\"CV-3\",\"summary\":" + MARKER + "}")));
        model.enqueueStream(Flux.just(FakeChatModel.text("다시 시도하세요"), FakeChatModel.finish("end_turn")));
        post("/api/chat/stream", "{\"message\":\"한 번 더 " + MARKER + "\"}");

        // 업스트림 오류 문구에 표식이 든다.
        model.enqueueStream(Flux.just(FakeChatModel.text("중간"))
                .concatWith(Flux.error(new IllegalStateException(MARKER))));
        post("/api/chat/stream", "{\"message\":\"세 번째 " + MARKER + "\"}");

        get("/api/knowledge/search?q=" + URLEncoder.encode(MARKER + " 알람", StandardCharsets.UTF_8));

        await().atMost(Duration.ofSeconds(10)).until(() -> spanNamed("controltower.command.propose") != null);
        tracerProvider.forceFlush().join(5, TimeUnit.SECONDS);
        List<SpanData> all = spans.getFinishedSpanItems();
        // 아래 표식 검사가 빈 목록을 보고 통과하지 않게, 검색 요청의 span이 실제로 수집됐는지 먼저 본다.
        assertThat(all).as(names(all).toString())
                .anyMatch(s -> s.getKind() == SpanKind.SERVER && s.getName().contains("/api/knowledge/search"));

        SpanData proposeSpan = spanNamed("controltower.command.propose");
        List<SpanData> trace = all.stream().filter(s -> s.getTraceId().equals(proposeSpan.getTraceId())).toList();
        assertThat(trace).as(names(trace).toString())
                .anyMatch(s -> s.getKind() == SpanKind.SERVER && s.getName().contains("/api/chat/stream"))
                .anyMatch(s -> s.getName().contains("chat_client"))
                .anyMatch(s -> s.getName().contains("proposeMaintenance"));
        SpanData tool = trace.stream().filter(s -> s.getName().contains("proposeMaintenance")).findFirst().orElseThrow();
        assertThat(proposeSpan.getParentSpanId()).isEqualTo(tool.getSpanId());

        for (SpanData span : all) {
            assertThat(render(span)).as(span.getName()).doesNotContain(MARKER);
        }
        assertNoMeterTagContains(MARKER);
        assertThat(output.getAll()).doesNotContain(MARKER);
    }

    // 웹 어댑터는 서비스 호출을 boundedElastic으로 넘긴다. 그 스레드에서도 HTTP 요청의 추적이 이어져야 한다.
    @Test
    void decideOverHttpOnBoundedElasticJoinsTheRequestTrace() throws Exception {
        assertThat(post("/api/session", "{\"accountId\":\"operator\"}").statusCode()).isEqualTo(200);
        spans.reset();

        post("/api/commands/" + UUID.randomUUID() + "/reject", "");

        await().atMost(Duration.ofSeconds(10)).until(() -> spanNamed("controltower.command.decide") != null);
        tracerProvider.forceFlush().join(5, TimeUnit.SECONDS);
        SpanData decideSpan = spanNamed("controltower.command.decide");
        assertThat(spans.getFinishedSpanItems())
                .filteredOn(s -> s.getSpanId().equals(decideSpan.getParentSpanId()))
                .singleElement()
                .satisfies(parent -> assertThat(parent.getKind()).isEqualTo(SpanKind.SERVER));
    }

    // 현황 조회의 병렬 읽기는 가상 스레드 실행기에서 돈다. 그 읽기도 요청의 추적에 붙어야 한다.
    @Test
    void parallelPlantReadsJoinTheRequestTrace() throws Exception {
        spans.reset();

        assertThat(get("/api/plant/status").statusCode()).isEqualTo(200);

        await().atMost(Duration.ofSeconds(10)).until(() -> spans.getFinishedSpanItems().stream()
                .anyMatch(s -> s.getKind() == SpanKind.SERVER && s.getName().contains("/api/plant/status")));
        tracerProvider.forceFlush().join(5, TimeUnit.SECONDS);
        SpanData server = spans.getFinishedSpanItems().stream()
                .filter(s -> s.getKind() == SpanKind.SERVER && s.getName().contains("/api/plant/status"))
                .findFirst().orElseThrow();
        assertThat(spans.getFinishedSpanItems())
                .filteredOn(s -> s.getName().equals("controltower.equipment.call"))
                .filteredOn(s -> s.getTraceId().equals(server.getTraceId()))
                .hasSizeGreaterThanOrEqualTo(3);
    }

    private SpanData spanNamed(String name) {
        return spans.getFinishedSpanItems().stream().filter(s -> s.getName().equals(name)).findFirst().orElse(null);
    }

    private static List<String> names(List<SpanData> spans) {
        return spans.stream().map(SpanData::getName).toList();
    }

    private static String render(SpanData span) {
        List<String> parts = new ArrayList<>();
        parts.add(span.getName());
        parts.add(span.getAttributes().toString());
        parts.add(span.getStatus().toString());
        span.getEvents().forEach(e -> parts.add(e.getName() + e.getAttributes()));
        span.getLinks().forEach(l -> parts.add(l.getAttributes().toString()));
        return String.join("|", parts);
    }

    private static Set<String> names(Enum<?>[] values) {
        return Stream.of(values).map(Enum::name).collect(java.util.stream.Collectors.toSet());
    }

    @SafeVarargs
    private static Set<String> union(Set<String>... sets) {
        Set<String> all = new HashSet<>();
        Arrays.stream(sets).forEach(all::addAll);
        return all;
    }
}
