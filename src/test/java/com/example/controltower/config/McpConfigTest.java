package com.example.controltower.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.example.controltower.application.port.in.PlantStatus;
import com.example.controltower.application.port.in.ProposeCommand;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.web.server.WebFilter;

class McpConfigTest {

    // 토큰은 application.yaml에서 MCP_TOKEN으로 채운다. 여기서는 yaml을 읽지 않아 개발자 셸의 환경 변수와 무관하다.
    private final ReactiveWebApplicationContextRunner runner = new ReactiveWebApplicationContextRunner()
            .withUserConfiguration(McpConfig.class)
            .withBean(PlantStatus.class, () -> mock(PlantStatus.class))
            .withBean(ProposeCommand.class, () -> mock(ProposeCommand.class))
            .withBean(ToolExecutionExceptionProcessor.class, () -> e -> "{}");

    @Test
    void enabledWithoutToken_startupFails() {
        runner.withPropertyValues("controltower.mcp.enabled=true")
                .run(ctx -> assertThat(ctx).hasFailed());
        runner.withPropertyValues("controltower.mcp.enabled=true", "controltower.mcp.token= ")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void enabledWithToken_registersFilter() {
        runner.withPropertyValues("controltower.mcp.enabled=true", "controltower.mcp.token=t")
                .run(ctx -> assertThat(ctx).hasNotFailed().hasSingleBean(WebFilter.class));
    }

    @Test
    void disabled_noFilterNoTokenNeeded() {
        runner.run(ctx -> assertThat(ctx).hasNotFailed().doesNotHaveBean(WebFilter.class));
    }
}
