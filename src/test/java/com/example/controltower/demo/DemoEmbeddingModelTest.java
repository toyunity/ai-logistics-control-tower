package com.example.controltower.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.controltower.adapter.out.knowledge.KnowledgeDocument;
import java.io.IOException;
import java.io.InputStream;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

class DemoEmbeddingModelTest {

    private static final Map<String, String> PARAPHRASES = Map.ofEntries(
            Map.entry("알람 205 기계 고장이 떴어요", "cv-alarm-205"),
            Map.entry("알람 101은 무슨 뜻인가요?", "cv-alarm-101"),
            Map.entry("슈트가 만재되면 어떻게 대응하나요?", "chute-full-procedure"),
            Map.entry("스캐너 읽기 실패율이 올라갔어요", "scanner-manual"),
            Map.entry("로봇 배터리가 저하되면 작업은 어떻게 하나요?", "amr-battery-procedure"),
            Map.entry("장애 등급 기준 알려주세요", "severity-grades"),
            Map.entry("정비 요청은 어떻게 등록하나요?", "maintenance-request"),
            Map.entry("우회 제안이 거절되는 경우는요?", "divert-denials"),
            Map.entry("확인 카드는 언제 만료되나요?", "confirm-card"),
            Map.entry("작업자가 구역에 있으면 재가동이 되나요?", "worker-in-zone"));

    private static final List<String> UNRELATED = List.of(
            "오늘 서울 날씨 어때요?",
            "파이썬으로 정렬 알고리즘 짜는 법 알려줘",
            "김치찌개 맛있게 끓이는 법이 궁금해요",
            "강아지가 밥을 안 먹어요",
            "안녕하세요",
            "너는 누구야?",
            "넌 AI야?",
            "환불은 어떻게 하나요?",
            "영업시간이 어떻게 되나요?",
            "화성까지 가는 데 얼마나 걸리나요?",
            "라면은 어떻게 끓이나요?",
            "이혼은 어떻게 하나요?",
            "자동차 할부는 얼마인가요?",
            "서울에서 부산까지 얼마나 걸리나요?",
            "운전면허는 어떻게 따나요?",
            "사장님 이름이 뭐예요?",
            "주식 투자는 어떻게 하나요?",
            "회사 위치가 어디인가요?",
            "비트코인 얼마인가요?",
            "제주도 여행은 어떻게 가나요?",
            "점심 메뉴 추천해 주세요",
            "오늘 몇 시인가요?",
            "노래 추천해 줘",
            "영어 공부는 어떻게 하나요?",
            "회사 전화번호가 뭐예요?",
            "아파트 가격은 얼마인가요?",
            "고마워요",
            "감사합니다",
            "커피는 얼마나 마셔야 하나요?",
            "지구에서 달까지 얼마나 걸리나요?",
            "그 기간이 지나면 어떻게 되나요?");

    // 어떤 임계값도 못 거르는 오답. 임진왜란은 바이그램 해시 충돌, 다이어트는 '컨베이어'와 겹치는 '이어'다.
    // 둘 다 가장 낮은 정답(알람 101, 0.223)보다 높다. 고치면 README도 함께 고친다.
    private static final List<String> KNOWN_LEAKS = List.of(
            "임진왜란은 몇 년에 일어났나요?",
            "다이어트는 어떻게 하나요?");

    private static List<KnowledgeDocument> docs;
    private static double threshold;

    private final DemoEmbeddingModel model = new DemoEmbeddingModel();

    @BeforeAll
    static void load() throws IOException {
        try (InputStream in = new ClassPathResource("knowledge/documents.json").getInputStream()) {
            docs = JsonMapper.builder().build().readValue(in, new TypeReference<List<KnowledgeDocument>>() {});
        }
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application-demo.yaml"));
        threshold = Double.parseDouble(yaml.getObject().getProperty("controltower.similarity-threshold"));
    }

    // 설계가 정한 측정 규모다. 질문을 빼서 통과시키지 않게 크기를 고정해 둔다.
    @Test
    void measuredOnEnoughQuestions() {
        assertThat(PARAPHRASES).hasSizeGreaterThanOrEqualTo(9);
        assertThat(UNRELATED).hasSizeGreaterThanOrEqualTo(20);
    }

    @Test
    void vectorIsDeterministicUnitLengthAnd1536Dimensional() {
        float[] v = model.embed("환불은 어떻게 하나요?");

        assertThat(v).hasSize(1536).isEqualTo(model.embed("환불은 어떻게 하나요?"));
        assertThat(cosine(v, v)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-5));
        assertThat(model.dimensions()).isEqualTo(1536);
    }

    @Test
    void bigramsStayWithinWords() {
        assertThat(model.embed("환 불")).containsOnly(0f);
        assertThat(model.embed("환불, 교환\n")).isEqualTo(model.embed("환불 교환"));
    }

    @Test
    void questionEndingsAreDropped() {
        assertThat(model.embed("환불 어떻게 하나요")).isEqualTo(model.embed("환불"));
    }

    @Test
    void tooShortTextGivesZeroVectorNotNaN() {
        assertThat(model.embed("?")).containsOnly(0f);
    }

    @Test
    void paraphrasesRankTheirDocumentFirstAboveThreshold() {
        PARAPHRASES.forEach((question, expected) -> {
            float[] q = model.embed(question);
            KnowledgeDocument top = docs.stream().max(Comparator.comparingDouble(f -> score(q, f))).orElseThrow();
            assertThat(top.id()).as(question).isEqualTo(expected);
            assertThat(score(q, top)).as(question).isGreaterThanOrEqualTo(threshold);
        });
    }

    @Test
    void unrelatedQuestionsScoreBelowThresholdOnEveryDocument() {
        for (String question : UNRELATED) {
            float[] q = model.embed(question);
            assertThat(docs).as(question).allSatisfy(f -> assertThat(score(q, f)).isLessThan(threshold));
        }
    }

    @Test
    void knownLeaksStillPassThreshold() {
        for (String question : KNOWN_LEAKS) {
            float[] q = model.embed(question);
            assertThat(docs).as(question).anySatisfy(f -> assertThat(score(q, f)).isGreaterThanOrEqualTo(threshold));
        }
    }

    // KnowledgeSync가 저장하는 문서 형식과 같아야 실제 검색 점수와 일치한다.
    private double score(float[] query, KnowledgeDocument f) {
        return cosine(query, model.embed("제목: " + f.title() + "\n내용: " + f.body()));
    }

    private static double cosine(float[] a, float[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            sum += a[i] * b[i];
        }
        return sum;
    }
}
