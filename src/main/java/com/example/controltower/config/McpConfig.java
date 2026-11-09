package com.example.controltower.config;

import com.example.controltower.adapter.in.mcp.McpReadTools;
import com.example.controltower.adapter.in.mcp.McpTokenGuard;
import com.example.controltower.adapter.in.tool.PlantTools;
import com.example.controltower.application.port.in.PlantStatus;
import com.example.controltower.application.port.in.ProposeCommand;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import java.util.List;
import org.springframework.ai.mcp.customizer.McpSyncServerCustomizer;
import org.springframework.ai.mcp.server.webflux.transport.WebFluxStreamableServerTransportProvider;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;

// 자동 변환(spring.ai.mcp.server.tool-callback-converter)은 꺼 둔다. 켜면 채팅용 ToolCallbackProvider의 제안 도구까지 MCP에 열린다.
// 설정 조합(프로토콜, 스타터 활성 여부)은 McpSettingsGuard가 막는다.
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

    // 스타터의 라우터와 같은 이름이라 스타터는 자기 라우터를 만들지 않는다(@ConditionalOnMissingBean(name)). 토큰 없는 라우터가 남지 않는다.
    @Bean
    @SuppressWarnings("unchecked")
    RouterFunction<ServerResponse> webFluxStreamableServerRouterFunction(
            WebFluxStreamableServerTransportProvider transport, @Value("${controltower.mcp.token:}") String token) {
        return ((RouterFunction<ServerResponse>) transport.getRouterFunction()).filter(new McpTokenGuard(token));
    }
}
