package com.example.controltower.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;

class DetectionThreadsTest {

    @Test
    void contextCloseShutsDownDetectionPool() {
        ServiceConfig.DetectionThreads threads = new ServiceConfig().detectionThreads();
        try (var ctx = new GenericApplicationContext()) {
            ctx.registerBean(ServiceConfig.DetectionThreads.class, () -> threads);
            ctx.refresh();
            assertThat(threads.pool().isShutdown()).isFalse();
        }

        assertThat(threads.pool().isShutdown()).isTrue();
    }
}
