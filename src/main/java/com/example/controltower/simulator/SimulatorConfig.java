package com.example.controltower.simulator;

import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "controltower.simulator.enabled", havingValue = "true")
@EnableScheduling
public class SimulatorConfig {

    // 이벤트 ID가 시드에서 나온다. 고정하면 재기동 후 이벤트가 DB의 중복 제거에 걸려 버려지므로 기본값은 기동마다 달라야 한다.
    @Bean
    PlantSimulator plantSimulator(Environment env) {
        Long configured = env.getProperty("controltower.simulator.seed", Long.class);
        long seed = configured != null ? configured : ThreadLocalRandom.current().nextLong();
        return new PlantSimulator(seed, Instant.now());
    }

    @Bean
    SimulatorRunner simulatorRunner(PlantSimulator simulator, Environment env) {
        return new SimulatorRunner(simulator, env);
    }
}
