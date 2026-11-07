package com.example.controltower.application.port.in;

import com.example.controltower.domain.DenialCode;
import com.example.controltower.domain.PendingCommand;

public sealed interface ProposalResult {

    record Proposed(PendingCommand command) implements ProposalResult {}

    record Rejected(DenialCode code) implements ProposalResult {}
}
