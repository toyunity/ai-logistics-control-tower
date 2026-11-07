package com.example.controltower.application.port.out;

import com.example.controltower.domain.Incident;
import com.example.controltower.domain.Severity;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface Incidents {

    List<Incident> open();

    /** 같은 설비·종류의 열린 건이 있으면 아무것도 하지 않는다. */
    void open(Incident incident);

    void close(UUID id, Instant at);

    void regrade(UUID id, Severity severity);
}
