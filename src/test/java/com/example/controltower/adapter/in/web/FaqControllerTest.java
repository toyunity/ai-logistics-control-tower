package com.example.controltower.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.controltower.config.ControlTowerProperties;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(FaqController.class)
@EnableConfigurationProperties(ControlTowerProperties.class) // 슬라이스 테스트에는 @ConfigurationPropertiesScan이 적용되지 않는다
class FaqControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean VectorStore vectorStore;

    @Test
    void searchMapsDocumentsToHits() throws Exception {
        Document doc = Document.builder()
                .id("refund-policy")
                .text("Q: 환불?\nA: 7일")
                .metadata(Map.of("question", "환불?", "answer", "7일"))
                .score(0.87)
                .build();
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(doc));

        mvc.perform(get("/api/faqs/search").param("q", "환불"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("refund-policy"))
                .andExpect(jsonPath("$[0].question").value("환불?"))
                .andExpect(jsonPath("$[0].answer").value("7일"))
                .andExpect(jsonPath("$[0].score").value(0.87));
    }

    @Test
    void blankQueryReturns400() throws Exception {
        mvc.perform(get("/api/faqs/search").param("q", " ")).andExpect(status().isBadRequest());
    }

    @Test
    void tooLongQueryReturns400() throws Exception {
        mvc.perform(get("/api/faqs/search").param("q", "a".repeat(501))).andExpect(status().isBadRequest());
    }
}
