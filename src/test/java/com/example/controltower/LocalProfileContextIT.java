package com.example.controltower;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.controltower.support.TestInfraConfig;
import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

// 실행 중인 Ollama 없이도 뜬다. 모델 호출은 대화할 때만 일어난다.
@SpringBootTest
@ActiveProfiles("local")
@Import(TestInfraConfig.class)
class LocalProfileContextIT {

    @Autowired ApplicationContext ctx;

    @Test
    void localProfileAnswersWithOllamaAndEmbedsWithGemini() {
        assertThat(ctx.getBean(ChatModel.class)).isInstanceOf(OllamaChatModel.class);
        assertThat(ctx.getBeansOfType(AnthropicChatModel.class)).isEmpty();
        assertThat(ctx.getBeansOfType(EmbeddingModel.class)).hasSize(1);
        assertThat(ctx.getBean(EmbeddingModel.class)).isInstanceOf(GoogleGenAiTextEmbeddingModel.class);
    }
}
