package com.example.controltower.config;

import com.example.controltower.adapter.in.tool.PlantTools;
import com.example.controltower.application.port.in.AssistantChat;
import com.example.controltower.application.port.in.DecideCommand;
import com.example.controltower.application.port.in.DetectAnomalies;
import com.example.controltower.application.port.in.IngestEvent;
import com.example.controltower.application.port.in.MaintenanceAdmin;
import com.example.controltower.application.port.in.PlantStatus;
import com.example.controltower.application.port.in.ProposeCommand;
import com.example.controltower.application.port.out.CommandAudit;
import com.example.controltower.application.port.out.ControlAssistant;
import com.example.controltower.application.port.out.Conveyors;
import com.example.controltower.application.port.out.EventLog;
import com.example.controltower.application.port.out.Incidents;
import com.example.controltower.application.port.out.MaintenanceDesk;
import com.example.controltower.application.port.out.PendingCommands;
import com.example.controltower.application.port.out.RobotFleet;
import com.example.controltower.application.port.out.SafetySignals;
import com.example.controltower.application.port.out.Sorters;
import com.example.controltower.application.port.out.UnitOfWork;
import com.example.controltower.application.service.AssistantChatService;
import com.example.controltower.application.service.DecideCommandService;
import com.example.controltower.application.service.DetectAnomaliesService;
import com.example.controltower.application.service.IngestEventService;
import com.example.controltower.application.service.MaintenanceAdminService;
import com.example.controltower.application.service.PlantStatusService;
import com.example.controltower.application.service.ProposeCommandService;
import com.example.controltower.domain.LineTopology;
import com.example.controltower.domain.SafetyLimits;
import com.example.controltower.domain.SafetyPolicy;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalTime;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ServiceConfig {

    private static final Duration CARD_TTL = Duration.ofMinutes(2);
    private static final SafetyLimits SAFETY_LIMITS = new SafetyLimits(Duration.ofSeconds(3), 30);
    // 설비 읽기는 타임아웃까지 블로킹하므로 요청이 몰려도 스레드가 모자라지 않게 가상 스레드를 쓴다.
    private static final Executor PLANT_READS = Executors.newVirtualThreadPerTaskExecutor();

    @Bean
    Clock clock() {
        return Clock.systemDefaultZone();
    }

    @Bean
    AssistantChat assistantChat(ControlAssistant assistant, ControlTowerProperties props) {
        return new AssistantChatService(assistant, props.streamIdleTimeout());
    }

    @Bean
    ToolCallbackProvider plantTools(PlantStatus status, ProposeCommand propose) {
        return MethodToolCallbackProvider.builder().toolObjects(new PlantTools(status, propose)).build();
    }

    @Bean
    PlantStatus plantStatus(
            Conveyors conveyors,
            Sorters sorters,
            RobotFleet fleet,
            EventLog events,
            Incidents incidents,
            MaintenanceDesk desk,
            Clock clock) {
        return new PlantStatusService(conveyors, sorters, fleet, events, incidents, desk, LineTopology.DEFAULT, clock,
                PLANT_READS);
    }

    @Bean
    ProposeCommand proposeCommand(
            Conveyors conveyors,
            Sorters sorters,
            RobotFleet fleet,
            SafetySignals safety,
            PendingCommands pending,
            MaintenanceDesk desk,
            UnitOfWork unitOfWork,
            Clock clock) {
        return new ProposeCommandService(conveyors, sorters, fleet, safety, pending, desk, unitOfWork,
                new SafetyPolicy(SAFETY_LIMITS), LineTopology.DEFAULT, clock, CARD_TTL);
    }

    @Bean
    DecideCommand decideCommand(
            Conveyors conveyors,
            Sorters sorters,
            RobotFleet fleet,
            SafetySignals safety,
            PendingCommands pending,
            MaintenanceDesk desk,
            CommandAudit audit,
            UnitOfWork unitOfWork,
            Clock clock) {
        return new DecideCommandService(conveyors, sorters, fleet, safety, pending, desk, audit, unitOfWork,
                new SafetyPolicy(SAFETY_LIMITS), LineTopology.DEFAULT, clock);
    }

    // 스케줄러와 수신이 같은 인스턴스의 잠금을 써야 감지 주기가 겹치지 않는다. 그래서 수신은 이 빈을 주입받는다.
    @Bean
    DetectAnomalies detectAnomalies(
            Conveyors conveyors,
            Sorters sorters,
            RobotFleet fleet,
            EventLog events,
            Incidents incidents,
            Clock clock,
            @Value("${controltower.cutoff}") String cutoff) {
        return new DetectAnomaliesService(
                conveyors, sorters, fleet, events, incidents, LineTopology.DEFAULT, clock, LocalTime.parse(cutoff));
    }

    // 실행 중 하나와 대기 하나만 둔다. 이벤트가 몰려도 감지가 줄줄이 쌓이지 않고, 버린 몫은 5초 주기가 맡는다.
    // Executor 빈으로 내놓으면 스프링 기본 작업 실행기가 꺼지므로 빈으로 등록하지 않는다.
    @Bean
    IngestEvent ingestEvent(EventLog events, DetectAnomalies detection) {
        ThreadPoolExecutor detectionExecutor = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(1), Thread.ofPlatform().name("ingest-detection").daemon().factory(),
                new ThreadPoolExecutor.DiscardPolicy());
        detectionExecutor.allowCoreThreadTimeOut(true);
        return new IngestEventService(events, LineTopology.DEFAULT, detection, detectionExecutor);
    }

    @Bean
    MaintenanceAdmin maintenanceAdmin(MaintenanceDesk desk, Clock clock) {
        return new MaintenanceAdminService(desk, clock);
    }
}
