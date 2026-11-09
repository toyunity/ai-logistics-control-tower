package com.example.controltower.adapter.out.knowledge;

import com.example.controltower.config.ControlTowerProperties;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Component
public class KnowledgeSync implements ApplicationRunner {

    // 임베딩하는 문장 형식이다. 해시에 넣어 형식이 바뀌면 전체를 다시 임베딩한다.
    static final String TEXT_FORMAT = "제목: %s\n내용: %s";

    private final VectorStore vectorStore;
    private final JdbcTemplate jdbc;
    private final JsonMapper jsonMapper;
    private final ControlTowerProperties props;

    public KnowledgeSync(VectorStore vectorStore, JdbcTemplate jdbc, JsonMapper jsonMapper, ControlTowerProperties props) {
        this.vectorStore = vectorStore;
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
        this.props = props;
    }

    // 임베딩 실패는 그대로 던져 기동을 막는다. 낡은 문서로 답하는 것보다 낫다.
    @Override
    public void run(ApplicationArguments args) throws Exception {
        try (InputStream in = new ClassPathResource("knowledge/documents.json").getInputStream()) {
            sync(jsonMapper.readValue(in, new TypeReference<List<KnowledgeDocument>>() {}));
        }
    }

    // 여러 인스턴스가 동시에 떠도 upsert라 결과가 같으므로 락을 걸지 않는다.
    public void sync(List<KnowledgeDocument> docs) {
        if (docs.isEmpty()) {
            throw new IllegalArgumentException("documents.json is empty");
        }
        Set<String> ids = new HashSet<>();
        for (KnowledgeDocument e : docs) {
            if (isBlank(e.id()) || isBlank(e.category()) || isBlank(e.title()) || isBlank(e.body())) {
                throw new IllegalArgumentException("Document fields must not be blank: " + e.id());
            }
            if (!ids.add(e.id())) {
                throw new IllegalArgumentException("Duplicate document id: " + e.id());
            }
        }

        Map<String, String> existing = new HashMap<>();
        jdbc.query("select id, metadata->>'hash' from vector_store", rs -> {
            existing.put(rs.getString(1), rs.getString(2));
        });

        List<Document> changed = docs.stream()
                .filter(e -> !hash(e, props.embeddingIndex(), TEXT_FORMAT).equals(existing.get(e.id())))
                .map(e -> Document.builder()
                        .id(e.id())
                        .text(TEXT_FORMAT.formatted(e.title(), e.body()))
                        .metadata(Map.of(
                                "title", e.title(),
                                "body", e.body(),
                                "category", e.category(),
                                "hash", hash(e, props.embeddingIndex(), TEXT_FORMAT)))
                        .build())
                .toList();
        if (!changed.isEmpty()) {
            vectorStore.add(changed);
        }

        List<String> removed = existing.keySet().stream().filter(id -> !ids.contains(id)).toList();
        if (!removed.isEmpty()) {
            vectorStore.delete(removed);
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    static String hash(KnowledgeDocument e, String embeddingIndex, String textFormat) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            // 필드 경계가 밀려도 해시가 같아지지 않게 NUL로 구분한다
            String joined = e.title() + '\u0000' + e.body() + '\u0000' + e.category()
                    + '\u0000' + embeddingIndex + '\u0000' + textFormat;
            return java.util.HexFormat.of().formatHex(md.digest(joined.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
