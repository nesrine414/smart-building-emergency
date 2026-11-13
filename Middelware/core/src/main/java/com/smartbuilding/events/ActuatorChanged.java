package com.smartbuilding.events;

import java.util.Map;

public record ActuatorChanged(Map<String, Object> data) {
}