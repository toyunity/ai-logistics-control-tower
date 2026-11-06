package com.example.consult.demo;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("demo")
class DemoEmbeddingModel implements EmbeddingModel {

    private static final int DIMENSIONS = 1536;

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<String> texts = request.getInstructions();
        List<Embedding> out = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            out.add(new Embedding(vector(texts.get(i)), i));
        }
        return new EmbeddingResponse(out);
    }

    @Override
    public float[] embed(Document document) {
        return vector(document.getText());
    }

    @Override
    public int dimensions() {
        return DIMENSIONS;
    }

    private static float[] vector(String text) {
        String s = text.replaceAll("\\s+", "");
        float[] v = new float[DIMENSIONS];
        for (int i = 0; i + 2 <= s.length(); i++) {
            v[bucket(s.substring(i, i + 2))]++;
        }
        double sum = 0;
        for (float x : v) {
            sum += x * x;
        }
        // 두 글자 미만이면 영벡터로 둔다. 나누면 NaN이 되어 pgvector가 거부한다.
        if (sum > 0) {
            float norm = (float) Math.sqrt(sum);
            for (int i = 0; i < v.length; i++) {
                v[i] /= norm;
            }
        }
        return v;
    }

    private static int bucket(String bigram) {
        CRC32 crc = new CRC32();
        crc.update(bigram.getBytes(StandardCharsets.UTF_8));
        return (int) (crc.getValue() % DIMENSIONS);
    }
}
