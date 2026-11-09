package com.example.controltower.application.port.in;

import java.util.List;

public interface SearchKnowledge {

    List<Hit> search(String query);

    record Hit(String id, String title, String body, double score) {}
}
