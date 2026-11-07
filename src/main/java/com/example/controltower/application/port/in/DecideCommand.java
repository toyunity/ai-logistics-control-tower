package com.example.controltower.application.port.in;

import com.example.controltower.domain.Actor;
import java.util.UUID;

public interface DecideCommand {

    CommandResult confirm(UUID id, Actor actor);

    CommandResult reject(UUID id, Actor actor);
}
