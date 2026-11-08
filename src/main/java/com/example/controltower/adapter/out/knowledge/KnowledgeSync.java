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

    // 임베딩 실패는 그대로 던져 기동을 막는다. 낡은 FAQ로 답하는 것보다 낫다.
    @Override
    public void run(ApplicationArguments args) throws Exception {
        try (InputStream in = new ClassPathResource("knowledge/documents.json").getInputStream()) {
            sync(jsonMapper.readValue(in, new TypeReference<List<KnowledgeDocument>>() {}));
        }
    }

    // 여러 인스턴스가 동시에 떠도 upsert라 결과가 같으므로 락을 걸지 않는다.
    public void sync(List<KnowledgeDocument> entries) {
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("faqs.json is empty");
        }
        Set<String> ids = new HashSet<>();
        for (KnowledgeDocument e : entries) {
            if (isBlank(e.id()) || isBlank(e.category()) || isBlank(e.title()) || isBlank(e.body())) {
                throw new IllegalArgumentException("FAQ fields must not be blank: " + e.id());
            }
            if (!ids.add(e.id())) {
                throw new IllegalArgumentException("Duplicate FAQ id: " + e.id());
            }
        }

        Map<String, String> existing = new HashMap<>();
        jdbc.query("select id, metadata->>'hash' from vector_store", rs -> {
            existing.put(rs.getString(1), rs.getString(2));
        });

        List<Document> changed = entries.stream()
                .filter(e -> !hash(e).equals(existing.get(e.id())))
                .map(e -> Document.builder()
                        .id(e.id())
                        .text("Q: " + e.title() + "\nA: " + e.body())
                        .metadata(Map.of(
                                "question", e.title(),
                                "answer", e.body(),
                                "category", e.category(),
                                "hash", hash(e)))
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

    private String hash(KnowledgeDocument e) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            // 필드 경계가 밀려도 해시가 같아지지 않게 NUL로 구분한다
            String joined = e.title() + '\u0000' + e.body() + '\u0000' + e.category()
                    + '\u0000' + props.embeddingIndex();
            return java.util.HexFormat.of().formatHex(md.digest(joined.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
