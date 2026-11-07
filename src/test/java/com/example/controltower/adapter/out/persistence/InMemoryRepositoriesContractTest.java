package com.example.controltower.adapter.out.persistence;

import com.example.controltower.application.port.out.EventLog;
import com.example.controltower.application.port.out.Incidents;
import com.example.controltower.application.port.out.MaintenanceDesk;
import com.example.controltower.application.port.out.PendingCommands;
import com.example.controltower.domain.CommandStatus;
import com.example.controltower.support.InMemoryCommandAudit;
import com.example.controltower.support.InMemoryEventLog;
import com.example.controltower.support.InMemoryIncidents;
import com.example.controltower.support.InMemoryMaintenanceDesk;
import com.example.controltower.support.InMemoryPendingCommands;
import java.util.List;
import java.util.UUID;

/** 메모리 대역이 JDBC 어댑터와 같은 약속을 지키는지 본다. 애플리케이션 테스트가 이 대역을 믿고 있다. */
class InMemoryRepositoriesContractTest {

    static class PendingCommandsDouble extends PendingCommandsContract {
        private final InMemoryCommandAudit audit = new InMemoryCommandAudit();
        private final InMemoryPendingCommands pending = new InMemoryPendingCommands(audit);

        @Override
        PendingCommands subject() {
            return pending;
        }

        @Override
        List<CommandStatus> auditedResults(UUID commandId) {
            return audit.entries().stream()
                    .filter(e -> e.commandId().equals(commandId))
                    .map(InMemoryCommandAudit.Entry::result)
                    .toList();
        }
    }

    static class EventLogDouble extends EventLogContract {
        private final InMemoryEventLog events = new InMemoryEventLog();

        @Override
        EventLog subject() {
            return events;
        }
    }

    static class IncidentsDouble extends IncidentsContract {
        private final InMemoryIncidents incidents = new InMemoryIncidents();

        @Override
        Incidents subject() {
            return incidents;
        }
    }

    static class MaintenanceDeskDouble extends MaintenanceDeskContract {
        private final InMemoryMaintenanceDesk desk = new InMemoryMaintenanceDesk();

        @Override
        MaintenanceDesk subject() {
            return desk;
        }
    }
}
