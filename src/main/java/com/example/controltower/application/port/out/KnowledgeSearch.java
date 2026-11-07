package com.example.controltower.application.port.out;

import java.util.List;

public interface KnowledgeSearch {
    List<KnowledgeHit> search(String query);
}
