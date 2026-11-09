package com.example.controltower.application.service;

import com.example.controltower.application.port.in.SearchKnowledge;
import com.example.controltower.application.port.out.KnowledgeSearch;
import java.util.List;

public class SearchKnowledgeService implements SearchKnowledge {

    private final KnowledgeSearch knowledge;

    public SearchKnowledgeService(KnowledgeSearch knowledge) {
        this.knowledge = knowledge;
    }

    @Override
    public List<Hit> search(String query) {
        return knowledge.search(query).stream()
                .map(h -> new Hit(h.id(), h.title(), h.body(), h.score()))
                .toList();
    }
}
