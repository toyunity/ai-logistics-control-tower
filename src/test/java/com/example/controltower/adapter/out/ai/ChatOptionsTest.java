package com.example.controltower.adapter.out.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaChatOptions;

class ChatOptionsTest {

    @Test
    void ollamaGetsItsOwnOptionsWithTheConfiguredModel() {
        var options = AssistantConfig.options(mock(OllamaChatModel.class), "qwen2.5:7b", 8000).build();

        assertThat(options).isInstanceOf(OllamaChatOptions.class).isInstanceOf(ToolCallingChatOptions.class);
        assertThat(options.getModel()).isEqualTo("qwen2.5:7b");
        assertThat(options.getMaxTokens()).isEqualTo(8000);
    }

    @Test
    void otherModelsKeepTheAnthropicOptions() {
        var options = AssistantConfig.options(mock(AnthropicChatModel.class), "claude-opus-5-5", 8000).build();

        assertThat(options).isInstanceOf(AnthropicChatOptions.class);
        assertThat(options.getModel()).isEqualTo("claude-opus-5-5");
    }
}
