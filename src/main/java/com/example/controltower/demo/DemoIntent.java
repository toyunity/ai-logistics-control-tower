package com.example.controltower.demo;

import java.util.Optional;

final class DemoIntent {

    record ToolRequest(String name, String argsJson) {}

    private DemoIntent() {}

    static Optional<ToolRequest> detect(String question) {
        return Optional.empty();
    }
}
