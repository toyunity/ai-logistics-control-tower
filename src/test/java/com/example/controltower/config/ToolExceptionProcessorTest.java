package com.example.controltower.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;
import org.springframework.ai.util.json.JsonParser;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class ToolExceptionProcessorTest {

    private static final String MARKER = "ARG-VALUE-MARKER";
    private final ToolExecutionExceptionProcessor processor = new ServiceConfig().toolExecutionExceptionProcessor();
    private final ToolDefinition tool =
            ToolDefinition.builder().name("count").description("개수").inputSchema("{}").build();

    @Test
    void jacksonCause_invalidArgument_logsClassNameOnly(CapturedOutput output) {
        Throwable cause = catchThrowable(() -> JsonParser.getJsonMapper().convertValue(MARKER, Integer.class));
        assertThat(cause.getMessage()).contains(MARKER);

        String result = processor.process(new ToolExecutionException(tool, cause));

        assertThat(result).contains("INVALID_ARGUMENT").doesNotContain(MARKER);
        assertThat(output.getOut() + output.getErr())
                .contains("WARN").contains(cause.getClass().getName()).doesNotContain(MARKER);
    }

    @Test
    void otherCause_unavailable_logsClassNameOnly(CapturedOutput output) {
        String result = processor.process(new ToolExecutionException(tool, new IllegalStateException(MARKER)));

        assertThat(result).contains("UNAVAILABLE").doesNotContain(MARKER);
        assertThat(output.getOut() + output.getErr())
                .contains("WARN").contains(IllegalStateException.class.getName()).doesNotContain(MARKER);
    }
}
