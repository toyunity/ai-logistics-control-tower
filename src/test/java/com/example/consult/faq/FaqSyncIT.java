package com.example.consult.faq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.consult.ConsultProperties;
import com.example.consult.support.FakeAiConfig;
import com.example.consult.support.FakeEmbeddingModel;
import com.example.consult.support.TestInfraConfig;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@Import({TestInfraConfig.class, FakeAiConfig.class})
class FaqSyncIT {

    @Autowired FaqSync faqSync;
    @Autowired JdbcTemplate jdbc;
    @Autowired FakeEmbeddingModel embeddings;
    @Autowired VectorStore vectorStore;
    @Autowired JsonMapper jsonMapper;
    @Autowired ConsultProperties props;

    int before;

    final FaqEntry a = new FaqEntry("a", "배송", "배송은 얼마나 걸리나요?", "2~3일 걸립니다.");
    final FaqEntry b = new FaqEntry("b", "환불", "환불은 어떻게 하나요?", "7일 이내 가능합니다.");

    @BeforeEach
    void clear() {
        jdbc.update("delete from vector_store");
        before = embeddings.embeddedTextCount();
    }

    long rows() {
        return jdbc.queryForObject("select count(*) from vector_store", Long.class);
    }

    @Test
    void syncIndexesAllEntries() {
        faqSync.sync(List.of(a, b));

        assertThat(rows()).isEqualTo(2);
        assertThat(embeddings.embeddedTextCount() - before).isEqualTo(2);
    }

    @Test
    void resyncWithoutChangesEmbedsNothing() {
        faqSync.sync(List.of(a, b));
        int afterFirst = embeddings.embeddedTextCount();

        faqSync.sync(List.of(a, b));

        assertThat(embeddings.embeddedTextCount()).isEqualTo(afterFirst);
    }

    @Test
    void changedAnswerReembedsOnlyThatEntry() {
        faqSync.sync(List.of(a, b));
        int afterFirst = embeddings.embeddedTextCount();

        faqSync.sync(List.of(a, new FaqEntry("b", "환불", b.question(), "14일 이내 가능합니다.")));

        assertThat(embeddings.embeddedTextCount() - afterFirst).isEqualTo(1);
        assertThat(jdbc.queryForObject("select content from vector_store where id = 'b'", String.class))
                .contains("14일");
        assertThat(jdbc.queryForObject("select metadata->>'answer' from vector_store where id = 'b'", String.class))
                .isEqualTo("14일 이내 가능합니다.");
    }

    @Test
    void changedEmbeddingIndexReembedsAllEntries() {
        faqSync.sync(List.of(a, b));
        int afterFirst = embeddings.embeddedTextCount();
        var otherIndex = new ConsultProperties(props.chatModel(), props.rewriteModel(), props.maxTokens(), props.topK(),
                props.similarityThreshold(), props.streamIdleTimeout(), "other-index");

        new FaqSync(vectorStore, jdbc, jsonMapper, otherIndex).sync(List.of(a, b));

        assertThat(embeddings.embeddedTextCount() - afterFirst).isEqualTo(2);
    }

    @Test
    void removedEntryIsDeleted() {
        faqSync.sync(List.of(a, b));

        faqSync.sync(List.of(a));

        assertThat(rows()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select id from vector_store", String.class)).isEqualTo("a");
    }

    @Test
    void duplicateIdFailsFast() {
        assertThatThrownBy(() -> faqSync.sync(List.of(a, a))).isInstanceOf(IllegalArgumentException.class);
        assertThat(rows()).isZero();
    }

    @Test
    void emptyListFailsFastAndKeepsExistingRows() {
        faqSync.sync(List.of(a, b));

        assertThatThrownBy(() -> faqSync.sync(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("faqs.json is empty");
        assertThat(rows()).isEqualTo(2);
    }

    @Test
    void blankFieldFailsFast() {
        assertThatThrownBy(() -> faqSync.sync(List.of(new FaqEntry("c", "배송", " ", "답"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(rows()).isZero();
    }
}
