package com.smartbuilding.entities;

import jakarta.nosql.Column;
import jakarta.nosql.Entity;
import jakarta.nosql.Id;

@Entity
public class Incident {

    @Id
    private String id;

    @Column
    private String buildingId;

    @Column
    private String roomId;

    @Column
    private String type;     // FIRE, GAS_LEAK, INTRUSION

    @Column
    private String severity; // LOW, MEDIUM, HIGH, CRITICAL

    @Column
    private String status;   // OPEN, RESOLVED

    @Column
    private long timestamp;

    public Incident() {}

    public Incident(String id, String buildingId, String roomId, String type, String severity, String status, long timestamp) {
        this.id = id;
        this.buildingId = buildingId;
        this.roomId = roomId;
        this.type = type;
        this.severity = severity;
        this.status = status;
        this.timestamp = timestamp;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getBuildingId() { return buildingId; }
    public void setBuildingId(String buildingId) { this.buildingId = buildingId; }

    public String getRoomId() { return roomId; }
    public void setRoomId(String roomId) { this.roomId = roomId; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
}
