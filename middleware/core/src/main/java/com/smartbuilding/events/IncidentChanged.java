package com.smartbuilding.events;

import com.smartbuilding.entities.Incident;

public record IncidentChanged(Incident incident, String change) {
}