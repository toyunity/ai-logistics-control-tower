package com.example.consult.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.consult.faq.FaqEntry;
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
            Map.entry("환불은 며칠 안에 가능한가요?", "refund-policy"),
            Map.entry("교환 신청은 어디서 할 수 있나요?", "exchange-policy"),
            Map.entry("배송비가 무료가 되는 금액이 얼마예요?", "shipping-fee"),
            Map.entry("주문 취소는 어떻게 하죠?", "order-cancel"),
            Map.entry("적립금은 언제까지 쓸 수 있어요?", "points-expiry"),
            Map.entry("비밀번호 재설정 링크를 받고 싶어요", "password-reset"),
            Map.entry("쿠폰 사용 방법 알려주세요", "coupon-usage"),
            Map.entry("카카오페이로 계산할 수 있어요?", "payment-methods"),
            Map.entry("구글 계정으로 새로 등록할 수 있나요?", "membership-signup"));

    private static final List<String> UNRELATED = List.of(
            "오늘 서울 날씨 어때요?",
            "파이썬으로 정렬 알고리즘 짜는 법 알려줘",
            "김치찌개 맛있게 끓이는 법이 궁금해요",
            "임진왜란은 몇 년에 일어났나요?",
            "강아지가 밥을 안 먹어요",
            "안녕하세요",
            "너는 누구야?",
            "넌 AI야?",
            "상담원 연결해 주세요",
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
            "다이어트는 어떻게 하나요?",
            "비트코인 얼마인가요?",
            "제주도 여행은 어떻게 가나요?",
            "점심 메뉴 추천해 주세요",
            "오늘 몇 시인가요?",
            "노래 추천해 줘",
            "영어 공부는 어떻게 하나요?",
            "회사 전화번호가 뭐예요?",
            "아파트 가격은 얼마인가요?",
            "고마워요");

    // 키워드 유사도의 한계로 남은 오답. 고치면 README도 함께 고친다.
    private static final List<String> KNOWN_LEAKS = List.of(
            "감사합니다",
            "커피는 얼마나 마셔야 하나요?",
            "지구에서 달까지 얼마나 걸리나요?",
            "그 기간이 지나면 어떻게 되나요?");

    private static List<FaqEntry> faqs;
    private static double threshold;

    private final DemoEmbeddingModel model = new DemoEmbeddingModel();

    @BeforeAll
    static void load() throws IOException {
        try (InputStream in = new ClassPathResource("faq/faqs.json").getInputStream()) {
            faqs = JsonMapper.builder().build().readValue(in, new TypeReference<List<FaqEntry>>() {});
        }
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application-demo.yaml"));
        threshold = Double.parseDouble(yaml.getObject().getProperty("consult.similarity-threshold"));
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
    void paraphrasesRankTheirFaqFirstAboveThreshold() {
        PARAPHRASES.forEach((question, expected) -> {
            float[] q = model.embed(question);
            FaqEntry top = faqs.stream().max(Comparator.comparingDouble(f -> score(q, f))).orElseThrow();
            assertThat(top.id()).as(question).isEqualTo(expected);
            assertThat(score(q, top)).as(question).isGreaterThanOrEqualTo(threshold);
        });
    }

    @Test
    void unrelatedQuestionsScoreBelowThresholdOnEveryFaq() {
        for (String question : UNRELATED) {
            float[] q = model.embed(question);
            assertThat(faqs).as(question).allSatisfy(f -> assertThat(score(q, f)).isLessThan(threshold));
        }
    }

    @Test
    void knownLeaksStillPassThreshold() {
        for (String question : KNOWN_LEAKS) {
            float[] q = model.embed(question);
            assertThat(faqs).as(question).anySatisfy(f -> assertThat(score(q, f)).isGreaterThanOrEqualTo(threshold));
        }
    }

    // FaqSync가 저장하는 문서 형식과 같아야 실제 검색 점수와 일치한다.
    private double score(float[] query, FaqEntry f) {
        return cosine(query, model.embed("Q: " + f.question() + "\nA: " + f.answer()));
    }

    private static double cosine(float[] a, float[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            sum += a[i] * b[i];
        }
        return sum;
    }
}
