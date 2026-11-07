package com.smartbuilding.services;

public interface ActuatorService {
    void send(String roomId, String actuator, String action);
}