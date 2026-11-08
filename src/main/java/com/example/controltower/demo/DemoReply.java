package com.example.controltower.demo;

final class DemoReply {

    static final String PREFIX = "[데모 응답 · 실제 AI 아님] ";

    private DemoReply() {}

    static String render(String toolName, String json) {
        return PREFIX;
    }
}
