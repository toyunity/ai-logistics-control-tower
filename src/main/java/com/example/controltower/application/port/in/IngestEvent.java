package com.example.controltower.application.port.in;

import com.example.controltower.domain.EquipmentEvent;

public interface IngestEvent {

    void ingest(EquipmentEvent event);
}
