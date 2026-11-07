package com.smartbuilding.entities;

import jakarta.nosql.Column;
import jakarta.nosql.Entity;
import jakarta.nosql.Id;
import java.util.ArrayList;
import java.util.List;

@Entity
public class Incident {

    @Id
    private String id;

    @Column
    private String buildingId;

    @Column
    private String roomId;

    @Column
    private String sensorId;

    @Column
    private String type;     // FIRE, GAS_LEAK

    @Column
    private String severity; // LOW, MEDIUM, HIGH, CRITICAL

    @Column
    private String status;   // OPEN, ACKNOWLEDGED, RESOLVED

    @Column
    private String description;

    @Column
    private boolean occupied;

    @Column
    private long timestamp;

    @Column
    private long updatedAt;

    @Column
    private List<String> affectedRoomIds = new ArrayList<>();

    @Column
    private String recommendation;

    public Incident() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getBuildingId() { return buildingId; }
    public void setBuildingId(String buildingId) { this.buildingId = buildingId; }

    public String getRoomId() { return roomId; }
    public void setRoomId(String roomId) { this.roomId = roomId; }

    public String getSensorId() { return sensorId; }
    public void setSensorId(String sensorId) { this.sensorId = sensorId; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public boolean isOccupied() { return occupied; }
    public void setOccupied(boolean occupied) { this.occupied = occupied; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }

    public List<String> getAffectedRoomIds() { return affectedRoomIds; }
    public void setAffectedRoomIds(List<String> affectedRoomIds) { this.affectedRoomIds = affectedRoomIds; }

    public String getRecommendation() { return recommendation; }
    public void setRecommendation(String recommendation) { this.recommendation = recommendation; }
}