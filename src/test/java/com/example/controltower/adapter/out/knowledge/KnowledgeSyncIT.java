package com.example.controltower.adapter.out.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.controltower.config.ControlTowerProperties;
import com.example.controltower.support.FakeAiConfig;
import com.example.controltower.support.FakeEmbeddingModel;
import com.example.controltower.support.TestInfraConfig;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@Import({TestInfraConfig.class, FakeAiConfig.class})
class KnowledgeSyncIT {

    @Autowired KnowledgeSync sync;
    @Autowired JdbcTemplate jdbc;
    @Autowired FakeEmbeddingModel embeddings;
    @Autowired VectorStore vectorStore;
    @Autowired JsonMapper jsonMapper;
    @Autowired ControlTowerProperties props;

    int before;

    final KnowledgeDocument a = new KnowledgeDocument("a", "알람 코드", "알람 101 하류 막힘", "하류를 먼저 확인한다.");
    final KnowledgeDocument b = new KnowledgeDocument("b", "대응 절차", "슈트 만재 대응", "같은 그룹으로 우회한다.");

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
        sync.sync(List.of(a, b));

        assertThat(rows()).isEqualTo(2);
        assertThat(embeddings.embeddedTextCount() - before).isEqualTo(2);
    }

    @Test
    void resyncWithoutChangesEmbedsNothing() {
        sync.sync(List.of(a, b));
        int afterFirst = embeddings.embeddedTextCount();

        sync.sync(List.of(a, b));

        assertThat(embeddings.embeddedTextCount()).isEqualTo(afterFirst);
    }

    @Test
    void changedBodyReembedsOnlyThatEntry() {
        sync.sync(List.of(a, b));
        int afterFirst = embeddings.embeddedTextCount();

        sync.sync(List.of(a, new KnowledgeDocument("b", b.category(), b.title(), "B 그룹으로 우회한다.")));

        assertThat(embeddings.embeddedTextCount() - afterFirst).isEqualTo(1);
        assertThat(jdbc.queryForObject("select metadata->>'body' from vector_store where id = 'b'", String.class))
                .isEqualTo("B 그룹으로 우회한다.");
    }

    @Test
    void storesTitleAndBodyInDocumentFormat() {
        sync.sync(List.of(a));

        assertThat(jdbc.queryForObject("select content from vector_store where id = 'a'", String.class))
                .isEqualTo("제목: 알람 101 하류 막힘\n내용: 하류를 먼저 확인한다.");
        assertThat(jdbc.queryForObject("select metadata->>'title' from vector_store where id = 'a'", String.class))
                .isEqualTo("알람 101 하류 막힘");
    }

    @Test
    void bundledDocumentsAreValidAndCoverAlarmCodes() throws Exception {
        List<KnowledgeDocument> docs;
        try (var in = new ClassPathResource("knowledge/documents.json").getInputStream()) {
            docs = jsonMapper.readValue(in, new TypeReference<List<KnowledgeDocument>>() {});
        }

        assertThat(docs).hasSizeBetween(15, 25);
        assertThat(docs).anySatisfy(d -> assertThat(d.title()).contains("101"));
        assertThat(docs).anySatisfy(d -> assertThat(d.title()).contains("205"));
        sync.sync(docs);
        assertThat(rows()).isEqualTo(docs.size());
    }

    @Test
    void changedEmbeddingIndexReembedsAllEntries() {
        sync.sync(List.of(a, b));
        int afterFirst = embeddings.embeddedTextCount();
        var otherIndex = new ControlTowerProperties(props.chatModel(), props.rewriteModel(), props.maxTokens(), props.topK(),
                props.similarityThreshold(), props.streamIdleTimeout(), "other-index");

        new KnowledgeSync(vectorStore, jdbc, jsonMapper, otherIndex).sync(List.of(a, b));

        assertThat(embeddings.embeddedTextCount() - afterFirst).isEqualTo(2);
    }

    @Test
    void removedEntryIsDeleted() {
        sync.sync(List.of(a, b));

        sync.sync(List.of(a));

        assertThat(rows()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select id from vector_store", String.class)).isEqualTo("a");
    }

    @Test
    void duplicateIdFailsFast() {
        assertThatThrownBy(() -> sync.sync(List.of(a, a))).isInstanceOf(IllegalArgumentException.class);
        assertThat(rows()).isZero();
    }

    @Test
    void emptyListFailsFastAndKeepsExistingRows() {
        sync.sync(List.of(a, b));

        assertThatThrownBy(() -> sync.sync(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("documents.json is empty");
        assertThat(rows()).isEqualTo(2);
    }

    @Test
    void blankFieldFailsFast() {
        assertThatThrownBy(() -> sync.sync(List.of(new KnowledgeDocument("c", "매뉴얼", " ", "내용"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(rows()).isZero();
    }

    @Test
    void textFormatChange_changesHash() {
        String idx = props.embeddingIndex();

        assertThat(KnowledgeSync.hash(a, idx, KnowledgeSync.TEXT_FORMAT))
                .isEqualTo(KnowledgeSync.hash(a, idx, KnowledgeSync.TEXT_FORMAT))
                .isNotEqualTo(KnowledgeSync.hash(a, idx, "title: %s\nbody: %s"));
    }
}
