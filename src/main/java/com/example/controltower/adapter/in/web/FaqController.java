package com.example.controltower.adapter.in.web;

import com.example.controltower.application.port.out.KnowledgeHit;
import com.example.controltower.application.port.out.KnowledgeSearch;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class FaqController {

    private final KnowledgeSearch knowledgeSearch;

    public FaqController(KnowledgeSearch knowledgeSearch) {
        this.knowledgeSearch = knowledgeSearch;
    }

    @GetMapping("/api/faqs/search")
    List<KnowledgeHit> search(@RequestParam @NotBlank @Size(max = 500) String q) {
        return knowledgeSearch.search(q);
    }
}
