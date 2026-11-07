package com.example.controltower.application.port.in;

import com.example.controltower.domain.Actor;
import com.example.controltower.domain.CommandSpec;

public interface ProposeCommand {

    ProposalResult propose(CommandSpec spec, Actor actor);
}
