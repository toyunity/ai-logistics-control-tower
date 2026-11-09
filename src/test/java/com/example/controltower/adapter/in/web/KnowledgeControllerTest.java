package com.example.controltower.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.controltower.adapter.out.knowledge.VectorKnowledgeSearch;
import com.example.controltower.application.service.SearchKnowledgeService;
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
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(KnowledgeController.class)
@Import({VectorKnowledgeSearch.class, SearchKnowledgeService.class}) // 문서를 응답으로 옮기는 매핑까지 함께 검증한다
@EnableConfigurationProperties(ControlTowerProperties.class) // 슬라이스 테스트에는 @ConfigurationPropertiesScan이 적용되지 않는다
class KnowledgeControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean VectorStore vectorStore;

    @Test
    void knowledgeSearchEndpoint() throws Exception {
        Document doc = Document.builder()
                .id("cv-alarm-101")
                .text("제목: 알람 101\n내용: 하류 막힘")
                .metadata(Map.of("title", "알람 101", "body", "하류 막힘"))
                .score(0.87)
                .build();
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(doc));

        mvc.perform(get("/api/knowledge/search").param("q", "알람 101"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("cv-alarm-101"))
                .andExpect(jsonPath("$[0].title").value("알람 101"))
                .andExpect(jsonPath("$[0].body").value("하류 막힘"))
                .andExpect(jsonPath("$[0].score").value(0.87));
    }

    @Test
    void blankQueryReturns400() throws Exception {
        mvc.perform(get("/api/knowledge/search").param("q", " ")).andExpect(status().isBadRequest());
    }

    @Test
    void tooLongQueryReturns400() throws Exception {
        mvc.perform(get("/api/knowledge/search").param("q", "a".repeat(501))).andExpect(status().isBadRequest());
    }
}
