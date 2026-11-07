package com.example.controltower.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.controltower.simulator.web.FleetController;
import com.example.controltower.simulator.web.SafetyController;
import com.example.controltower.simulator.web.SimControlController;
import com.example.controltower.simulator.web.VendorAController;
import com.example.controltower.simulator.web.VendorBController;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

class SimulatorConfigTest {

    private final ApplicationContextRunner contexts = new ApplicationContextRunner()
            .withUserConfiguration(
                    SimulatorConfig.class,
                    VendorAController.class,
                    VendorBController.class,
                    FleetController.class,
                    SafetyController.class,
                    SimControlController.class);

    private static String firstEventId(MockEnvironment env) {
        PlantSimulator sim = new SimulatorConfig().plantSimulator(env);
        sim.start("scanner-degraded");
        return sim.drainEvents().getFirst().eventId();
    }

    @Test
    void configuredSeed_isReproducible() {
        MockEnvironment env = new MockEnvironment().withProperty("controltower.simulator.seed", "7");

        assertThat(firstEventId(env)).isEqualTo(firstEventId(env));
    }

    @Test
    void unsetSeed_differsPerBoot_soEventIdsDontHitTheDedupeAfterARestart() {
        MockEnvironment env = new MockEnvironment();

        assertThat(firstEventId(env)).isNotEqualTo(firstEventId(env));
    }

    @Test
    void disabled_startsNothing() {
        contexts.withPropertyValues("controltower.simulator.enabled=false").run(ctx -> assertNothingStarted(ctx));
        contexts.run(ctx -> assertNothingStarted(ctx));
    }

    private static void assertNothingStarted(org.springframework.context.ApplicationContext ctx) {
        assertThat(ctx.getBeansOfType(PlantSimulator.class)).isEmpty();
        assertThat(ctx.getBeansOfType(SimulatorRunner.class)).isEmpty();
        assertThat(ctx.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class)).isEmpty();
        assertThat(ctx.getBeansOfType(VendorAController.class)).isEmpty();
        assertThat(ctx.getBeansOfType(VendorBController.class)).isEmpty();
        assertThat(ctx.getBeansOfType(FleetController.class)).isEmpty();
        assertThat(ctx.getBeansOfType(SafetyController.class)).isEmpty();
        assertThat(ctx.getBeansOfType(SimControlController.class)).isEmpty();
    }

    @Test
    void enabled_schedulesTheTicker() {
        contexts.withPropertyValues("controltower.simulator.enabled=true").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks()).hasSize(1);
            assertThat(ctx).hasSingleBean(VendorAController.class);
        });
    }
}
