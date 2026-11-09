package com.example.controltower.adapter.in.mcp;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * MCP로 여는 도구는 조회 네 개뿐이다. 제안 도구는 MCP 클라이언트에 화면 세션 같은 신원이 없어 카드 주인을 정할 수 없으므로 열지 않는다.
 * 이름 허용 목록이라 채팅 도구에 새 도구가 생겨도 여기로 새지 않는다.
 */
public final class McpReadTools {

    static final Set<String> NAMES = Set.of("getPlantStatus", "getEquipment", "getTimeline", "diagnose");

    private McpReadTools() {}

    public static List<SyncToolSpecification> of(ToolCallback[] all, ToolExecutionExceptionProcessor onError) {
        List<ToolCallback> reads = Arrays.stream(all)
                .filter(c -> NAMES.contains(c.getToolDefinition().name()))
                .<ToolCallback>map(c -> new Guarded(c, onError))
                .toList();
        // 이름이 바뀌면 조용히 빠지지 않고 기동이 실패한다.
        if (reads.size() != NAMES.size()) {
            throw new IllegalStateException("MCP read tools missing: expected " + NAMES);
        }
        return McpToolUtils.toSyncToolSpecification(reads);
    }

    // McpToolUtils는 예외 문구를 그대로 클라이언트에 돌려준다. 인자 변환 실패 문구에는 인자 값이 들어 있어 채팅과 같은 고정 결과로 바꾼다.
    private record Guarded(ToolCallback delegate, ToolExecutionExceptionProcessor onError) implements ToolCallback {

        @Override
        public ToolDefinition getToolDefinition() {
            return delegate.getToolDefinition();
        }

        @Override
        public ToolMetadata getToolMetadata() {
            return delegate.getToolMetadata();
        }

        @Override
        public String call(String input) {
            return call(input, null);
        }

        @Override
        public String call(String input, ToolContext ctx) {
            try {
                return delegate.call(input, ctx);
            } catch (ToolExecutionException e) {
                return onError.process(e);
            } catch (RuntimeException e) {
                return onError.process(new ToolExecutionException(getToolDefinition(), e));
            }
        }
    }
}
