package com.smartbuilding.entities;

import jakarta.nosql.Column;
import jakarta.nosql.Entity;
import jakarta.nosql.Id;

@Entity
public class Sensor {

    @Id
    private String id;

    @Column
    private String roomId;

    @Column
    private String type;   // SMOKE, TEMPERATURE, GAS, MOTION

    @Column
    private String status; // ACTIVE, ALERT, INACTIVE

    public Sensor() {}

    public Sensor(String id, String roomId, String type, String status) {
        this.id = id;
        this.roomId = roomId;
        this.type = type;
        this.status = status;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getRoomId() { return roomId; }
    public void setRoomId(String roomId) { this.roomId = roomId; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
