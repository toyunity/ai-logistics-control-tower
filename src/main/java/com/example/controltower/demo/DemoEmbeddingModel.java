package com.example.controltower.demo;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
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
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");
    // 질문 어미가 겹치는 것만으로 무관한 질문이 문서에 걸리지 않게 뺀다.
    private static final Set<String> STOP_WORDS = Set.of(
            "어떻게", "얼마나", "하나요", "있나요", "되나요", "인가요", "해요", "어때요",
            "싶어요", "하고", "주세요", "알려주세요", "뭐예요", "뭔가요", "언제", "어디서");

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
        float[] v = new float[DIMENSIONS];
        var words = WORD.matcher(text);
        while (words.find()) {
            String w = words.group();
            if (STOP_WORDS.contains(w)) continue;
            for (int i = 0; i + 2 <= w.length(); i++) {
                v[bucket(w.substring(i, i + 2))]++;
            }
        }
        double sum = 0;
        for (float x : v) {
            sum += x * x;
        }
        // 바이그램이 하나도 없으면 영벡터로 둔다. 나누면 NaN이 되어 pgvector가 거부한다.
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
