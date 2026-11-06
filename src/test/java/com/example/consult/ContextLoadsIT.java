package com.example.consult;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.consult.support.FakeAiConfig;
import com.example.consult.support.TestInfraConfig;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@Import({TestInfraConfig.class, FakeAiConfig.class})
class ContextLoadsIT {

    @Autowired ApplicationContext ctx;
    @Autowired JdbcTemplate jdbc;

    @Test
    void contextLoads() {
        assertThat(ctx.getBean(ConsultProperties.class).chatModel()).isEqualTo("claude-opus-5-5");
        assertThat(ctx.getBeansOfType(org.springframework.ai.anthropic.AnthropicChatModel.class)).hasSize(1);
        assertThat(ctx.getBeansOfType(org.springframework.ai.openai.OpenAiChatModel.class)).isEmpty();
        assertThat(ctx.getBean(VectorStore.class)).isInstanceOf(PgVectorStore.class);
        assertThat(ctx.getBean(ChatMemory.class)).isNotNull();
        var anthropicDefaults = ctx.getBean(org.springframework.ai.anthropic.AnthropicChatModel.class).getOptions();
        assertThat(anthropicDefaults.getTemperature()).isNull();
        assertThat(anthropicDefaults.getTopP()).isNull();
        assertThat(anthropicDefaults.getTopK()).isNull();
        assertThat(anthropicDefaults.getThinking()).isNull();
        assertThat(jdbc.queryForObject("select count(*) from vector_store", Long.class)).isNotNull();
        assertThat(jdbc.queryForObject("select count(*) from spring_ai_chat_memory", Long.class)).isNotNull();
    }
}
