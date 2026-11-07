package com.example.controltower.application.port.in;

import com.example.controltower.domain.Actor;
import java.util.UUID;

public interface MaintenanceAdmin {

    boolean close(UUID id, Actor actor);
}
