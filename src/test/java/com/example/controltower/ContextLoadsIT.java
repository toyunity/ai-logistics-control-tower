package com.example.controltower;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.controltower.application.port.in.AssistantChat;
import com.example.controltower.application.port.in.DecideCommand;
import com.example.controltower.application.port.in.DetectAnomalies;
import com.example.controltower.application.port.in.IngestEvent;
import com.example.controltower.application.port.in.MaintenanceAdmin;
import com.example.controltower.application.port.in.PlantStatus;
import com.example.controltower.application.port.in.ProposeCommand;
import com.example.controltower.config.ControlTowerProperties;
import java.util.List;
import com.example.controltower.support.FakeAiConfig;
import com.example.controltower.support.TestInfraConfig;
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
        assertThat(ctx.getBean(ControlTowerProperties.class).chatModel()).isEqualTo("claude-opus-5-5");
        assertThat(ctx.getBeansOfType(org.springframework.ai.anthropic.AnthropicChatModel.class)).hasSize(1);
        assertThat(ctx.getBeansOfType(org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingModel.class)).hasSize(1);
        assertThat(ctx.getBean(VectorStore.class)).isInstanceOf(PgVectorStore.class);
        assertThat(ctx.getBean(ChatMemory.class)).isNotNull();
        var anthropicDefaults = ctx.getBean(org.springframework.ai.anthropic.AnthropicChatModel.class).getOptions();
        assertThat(anthropicDefaults.getTemperature()).isNull();
        assertThat(anthropicDefaults.getTopP()).isNull();
        assertThat(anthropicDefaults.getTopK()).isNull();
        assertThat(anthropicDefaults.getThinking()).isNull();
        assertThat(jdbc.queryForObject("select count(*) from vector_store", Long.class)).isNotNull();
        assertThat(jdbc.queryForObject("select count(*) from spring_ai_chat_memory", Long.class)).isNotNull();
        for (Class<?> port : List.of(AssistantChat.class, PlantStatus.class, ProposeCommand.class, DecideCommand.class,
                IngestEvent.class, DetectAnomalies.class, MaintenanceAdmin.class)) {
            assertThat(ctx.getBeansOfType(port)).as(port.getSimpleName()).hasSize(1);
        }
    }
}
