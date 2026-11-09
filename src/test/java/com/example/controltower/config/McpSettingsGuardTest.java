package com.example.controltower.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class McpSettingsGuardTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(McpSettingsGuard.class);

    // 토큰 검사는 Streamable HTTP 라우터에만 붙는다. 그 밖의 조합은 MCP를 토큰 없이 열 수 있어 기동을 막는다.
    @ParameterizedTest
    @CsvSource(nullValues = "-", value = {
        // controltower.mcp.enabled, spring.ai.mcp.server.enabled, protocol, 기동
        "false, false, -,          true",
        "-,     false, -,          true",
        "true,  true,  STREAMABLE, true",
        "true,  true,  -,          false",
        "false, true,  -,          false",
        "-,     -,     -,          false",
        "true,  true,  SSE,        false",
        "true,  true,  STATELESS,  false",
        "true,  false, -,          false",
    })
    void unsafeCombinationsFailStartup(String ours, String starter, String protocol, boolean starts) {
        ApplicationContextRunner r = runner;
        if (ours != null) r = r.withPropertyValues("controltower.mcp.enabled=" + ours);
        if (starter != null) r = r.withPropertyValues("spring.ai.mcp.server.enabled=" + starter);
        if (protocol != null) r = r.withPropertyValues("spring.ai.mcp.server.protocol=" + protocol);

        r.run(ctx -> {
            if (starts) {
                assertThat(ctx).hasNotFailed();
            } else {
                assertThat(ctx).hasFailed();
            }
        });
    }
}
