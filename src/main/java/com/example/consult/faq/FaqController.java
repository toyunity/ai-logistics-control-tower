package com.example.consult.faq;

import com.example.consult.ConsultProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class FaqController {

    private final VectorStore vectorStore;
    private final ConsultProperties props;

    public FaqController(VectorStore vectorStore, ConsultProperties props) {
        this.vectorStore = vectorStore;
        this.props = props;
    }

    @GetMapping("/api/faqs/search")
    List<FaqHit> search(@RequestParam @NotBlank @Size(max = 500) String q) {
        List<Document> docs = vectorStore.similaritySearch(SearchRequest.builder()
                .query(q)
                .topK(props.topK())
                .similarityThreshold(props.similarityThreshold())
                .build());
        return docs.stream()
                .map(d -> new FaqHit(
                        d.getId(),
                        (String) d.getMetadata().get("question"),
                        (String) d.getMetadata().get("answer"),
                        d.getScore() == null ? 0 : d.getScore()))
                .toList();
    }
}
