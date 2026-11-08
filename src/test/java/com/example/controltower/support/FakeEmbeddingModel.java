package com.example.controltower.support;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

public class FakeEmbeddingModel implements EmbeddingModel {

    private static final int DIMENSIONS = 1536;
    private final AtomicInteger embeddedTextCount = new AtomicInteger();

    public int embeddedTextCount() {
        return embeddedTextCount.get();
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<String> texts = request.getInstructions();
        embeddedTextCount.addAndGet(texts.size());
        var out = new java.util.ArrayList<Embedding>();
        for (int i = 0; i < texts.size(); i++) {
            out.add(new Embedding(vector(texts.get(i)), i));
        }
        return new EmbeddingResponse(out);
    }

    @Override
    public float[] embed(Document document) {
        embeddedTextCount.incrementAndGet();
        return vector(document.getText());
    }

    @Override
    public int dimensions() {
        return DIMENSIONS;
    }

    private static float[] vector(String text) {
        float[] v = new float[DIMENSIONS];
        v[text != null && text.contains("205") ? 0 : 1] = 1f;
        return v;
    }
}
