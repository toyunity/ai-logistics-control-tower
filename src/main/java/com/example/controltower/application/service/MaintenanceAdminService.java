package com.example.controltower.application.service;

import com.example.controltower.application.port.in.MaintenanceAdmin;
import com.example.controltower.application.port.out.MaintenanceDesk;
import com.example.controltower.domain.Actor;
import com.example.controltower.domain.Role;
import java.time.Clock;
import java.util.UUID;

public final class MaintenanceAdminService implements MaintenanceAdmin {

    private final MaintenanceDesk desk;
    private final Clock clock;

    public MaintenanceAdminService(MaintenanceDesk desk, Clock clock) {
        this.desk = desk;
        this.clock = clock;
    }

    @Override
    public boolean close(UUID id, Actor actor) {
        return actor != null && actor.role() == Role.OPERATOR && desk.close(id, clock.instant());
    }
}
