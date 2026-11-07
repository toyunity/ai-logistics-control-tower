package com.example.controltower.config;

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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ServiceConfig {

    private static final Duration CARD_TTL = Duration.ofMinutes(2);
    private static final SafetyLimits SAFETY_LIMITS = new SafetyLimits(Duration.ofSeconds(3), 30);

    @Bean
    Clock clock() {
        return Clock.systemDefaultZone();
    }

    @Bean
    AssistantChat assistantChat(ControlAssistant assistant, ControlTowerProperties props) {
        return new AssistantChatService(assistant, props.streamIdleTimeout());
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
        return new PlantStatusService(conveyors, sorters, fleet, events, incidents, desk, LineTopology.DEFAULT, clock);
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

    @Bean
    IngestEvent ingestEvent(EventLog events, DetectAnomalies detection) {
        return new IngestEventService(events, LineTopology.DEFAULT, detection);
    }

    @Bean
    MaintenanceAdmin maintenanceAdmin(MaintenanceDesk desk, Clock clock) {
        return new MaintenanceAdminService(desk, clock);
    }
}
