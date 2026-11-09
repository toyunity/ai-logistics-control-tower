package com.example.controltower.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.example.controltower.application.port.in.PlantStatus;
import com.example.controltower.application.port.in.ProposeCommand;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.server.webflux.transport.WebFluxStreamableServerTransportProvider;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.web.server.WebFilter;
import tools.jackson.databind.json.JsonMapper;

class McpConfigTest {

    // 스타터의 라우터 빈 이름이다. 같은 이름의 빈이 있으면 스타터는 자기 라우터를 만들지 않는다(@ConditionalOnMissingBean(name)).
    private static final String ROUTER = "webFluxStreamableServerRouterFunction";

    // 토큰은 application.yaml에서 MCP_TOKEN으로 채운다. 여기서는 yaml을 읽지 않아 개발자 셸의 환경 변수와 무관하다.
    private final ReactiveWebApplicationContextRunner runner = new ReactiveWebApplicationContextRunner()
            .withUserConfiguration(McpConfig.class)
            .withBean(PlantStatus.class, () -> mock(PlantStatus.class))
            .withBean(ProposeCommand.class, () -> mock(ProposeCommand.class))
            .withBean(ToolExecutionExceptionProcessor.class, () -> e -> "{}")
            .withBean(WebFluxStreamableServerTransportProvider.class, () -> WebFluxStreamableServerTransportProvider.builder()
                    .jsonMapper(new JacksonMcpJsonMapper(JsonMapper.builder().build())).build());

    @Test
    void enabledWithoutToken_startupFails() {
        runner.withPropertyValues("controltower.mcp.enabled=true")
                .run(ctx -> assertThat(ctx).hasFailed());
        runner.withPropertyValues("controltower.mcp.enabled=true", "controltower.mcp.token= ")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void enabledWithToken_replacesTheStarterRouterWithAGuardedOne() {
        runner.withPropertyValues("controltower.mcp.enabled=true", "controltower.mcp.token=t")
                .run(ctx -> assertThat(ctx).hasNotFailed().hasBean(ROUTER).doesNotHaveBean(WebFilter.class));
    }

    @Test
    void disabled_noRouterNoTokenNeeded() {
        runner.run(ctx -> assertThat(ctx).hasNotFailed().doesNotHaveBean(ROUTER));
    }
}
