package com.example.controltower.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.controltower.application.port.out.PendingCommands;
import com.example.controltower.support.FakeAiConfig;
import com.example.controltower.support.TestInfraConfig;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

// 서버가 요청을 받기 시작한 뒤에 정리하면 그 사이 들어온 확정 요청이 이전 프로세스의 EXECUTING 행에 막힌다.
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import({TestInfraConfig.class, FakeAiConfig.class, StaleCommandCleanupIT.ServerStart.class})
class StaleCommandCleanupIT {

    static final AtomicReference<Boolean> cleanedBeforeServer = new AtomicReference<>();

    @MockitoSpyBean PendingCommands pending;

    @Test
    void cleanupRunsBeforeWebServerStarts() {
        assertThat(cleanedBeforeServer).hasValue(true);
    }

    @TestConfiguration
    static class ServerStart {

        @Bean
        ApplicationListener<WebServerInitializedEvent> recordCleanupAtServerStart(PendingCommands pending) {
            return e -> cleanedBeforeServer.compareAndSet(null, Mockito.mockingDetails(pending).getInvocations()
                    .stream()
                    .anyMatch(i -> i.getMethod().getName().equals("markStaleExecutingUnconfirmed")));
        }
    }
}
