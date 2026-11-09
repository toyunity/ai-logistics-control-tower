package com.example.controltower.config;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

// 토큰 검사는 Streamable HTTP 라우터(McpConfig)에만 붙는다. 스타터만 켜지거나 다른 프로토콜(SSE의 /sse 등)이면
// MCP가 토큰 없이 열리므로 기동을 막는다. 스타터는 spring.ai.mcp.server.enabled가 없으면 켜진다.
@Component
public class McpSettingsGuard {

    McpSettingsGuard(Environment env) {
        boolean ours = env.getProperty("controltower.mcp.enabled", Boolean.class, false);
        boolean starter = env.getProperty("spring.ai.mcp.server.enabled", Boolean.class, true);
        if (ours != starter) {
            throw new IllegalStateException("spring.ai.mcp.server.enabled must equal controltower.mcp.enabled");
        }
        if (ours && !"STREAMABLE".equalsIgnoreCase(env.getProperty("spring.ai.mcp.server.protocol", "STREAMABLE"))) {
            throw new IllegalStateException("MCP token guard covers only spring.ai.mcp.server.protocol=STREAMABLE");
        }
    }
}
