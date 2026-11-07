package com.example.controltower.adapter.in.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.controltower.application.port.in.DetectAnomalies;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

@ExtendWith(OutputCaptureExtension.class)
class AnomalySchedulerTest {

    private final AtomicInteger calls = new AtomicInteger();

    // 다른 설정의 @EnableScheduling 없이 이 클래스만 올려도 스케줄이 잡혀야 한다.
    private final ApplicationContextRunner contexts = new ApplicationContextRunner()
            .withBean(DetectAnomalies.class, () -> calls::incrementAndGet)
            .withUserConfiguration(AnomalyScheduler.class);

    @Test
    void enabled_schedulesDetectionOnItsOwn() {
        contexts.withPropertyValues("controltower.detection.enabled=true").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks()).hasSize(1);
        });
    }

    @Test
    void disabledOrUnset_schedulesNothing() {
        contexts.withPropertyValues("controltower.detection.enabled=false").run(ctx -> {
            assertThat(ctx).doesNotHaveBean(AnomalyScheduler.class);
            assertThat(ctx.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class)).isEmpty();
        });
        contexts.run(ctx -> assertThat(ctx).doesNotHaveBean(AnomalyScheduler.class));
    }

    @Test
    void failingCycle_isSwallowedWithFixedMessage_andNextCycleRuns(CapturedOutput output) {
        AnomalyScheduler scheduler = new AnomalyScheduler(() -> {
            calls.incrementAndGet();
            throw new IllegalStateException("SECRET-SQL-VALUE");
        });

        scheduler.run();
        scheduler.run();

        assertThat(calls).hasValue(2);
        assertThat(output.getOut() + output.getErr())
                .contains("anomaly detection cycle failed")
                .doesNotContain("SECRET-SQL-VALUE");
    }
}
