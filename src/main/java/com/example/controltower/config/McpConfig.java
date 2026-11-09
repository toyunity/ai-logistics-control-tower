package com.example.controltower.config;

import com.example.controltower.adapter.in.mcp.McpReadTools;
import com.example.controltower.adapter.in.mcp.McpTokenFilter;
import com.example.controltower.adapter.in.tool.PlantTools;
import com.example.controltower.application.port.in.PlantStatus;
import com.example.controltower.application.port.in.ProposeCommand;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import java.util.List;
import org.springframework.ai.mcp.customizer.McpSyncServerCustomizer;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// 자동 변환(spring.ai.mcp.server.tool-callback-converter)은 꺼 둔다. 켜면 채팅용 ToolCallbackProvider의 제안 도구까지 MCP에 열린다.
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "controltower.mcp.enabled", havingValue = "true")
public class McpConfig {

    @Bean
    List<SyncToolSpecification> mcpReadTools(
            PlantStatus status, ProposeCommand propose, ToolExecutionExceptionProcessor onError) {
        return McpReadTools.of(new PlantTools(status, propose).callbacks(), onError);
    }

    // SDK의 입력 스키마 검사는 검증기 문구(JVM 로캘에 따라 바뀐다)를 그대로 돌려준다. 끄면 인자 오류가 채팅과 같은 INVALID_ARGUMENT로 간다.
    @Bean
    McpSyncServerCustomizer mcpFixedArgumentErrors() {
        return spec -> spec.validateToolInputs(false);
    }

    @Bean
    McpTokenFilter mcpTokenFilter(
            @Value("${spring.ai.mcp.server.streamable-http.mcp-endpoint:/mcp}") String path,
            @Value("${controltower.mcp.token:}") String token) {
        return new McpTokenFilter(path, token);
    }
}
