package com.example.controltower.adapter.in.web;

import com.example.controltower.application.port.in.SearchKnowledge;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
public class KnowledgeController {

    private final SearchKnowledge searchKnowledge;

    public KnowledgeController(SearchKnowledge searchKnowledge) {
        this.searchKnowledge = searchKnowledge;
    }

    @GetMapping("/api/knowledge/search")
    Mono<List<SearchKnowledge.Hit>> search(@RequestParam @NotBlank @Size(max = 500) String q) {
        return Blocking.call(() -> searchKnowledge.search(q));
    }
}
