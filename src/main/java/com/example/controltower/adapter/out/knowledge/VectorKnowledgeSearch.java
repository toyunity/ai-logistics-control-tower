package com.example.controltower.adapter.out.knowledge;

import com.example.controltower.application.port.out.KnowledgeHit;
import com.example.controltower.application.port.out.KnowledgeSearch;
import com.example.controltower.config.ControlTowerProperties;
import java.util.List;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

@Component
public class VectorKnowledgeSearch implements KnowledgeSearch {

    private final VectorStore vectorStore;
    private final ControlTowerProperties props;

    public VectorKnowledgeSearch(VectorStore vectorStore, ControlTowerProperties props) {
        this.vectorStore = vectorStore;
        this.props = props;
    }

    @Override
    public List<KnowledgeHit> search(String query) {
        List<Document> docs = vectorStore.similaritySearch(SearchRequest.builder()
                .query(query)
                .topK(props.topK())
                .similarityThreshold(props.similarityThreshold())
                .build());
        return docs.stream()
                .map(d -> new KnowledgeHit(
                        d.getId(),
                        (String) d.getMetadata().get("title"),
                        (String) d.getMetadata().get("body"),
                        d.getScore() == null ? 0 : d.getScore()))
                .toList();
    }
}
