package com.smartbuilding.entities;

import jakarta.nosql.Column;
import jakarta.nosql.Entity;
import jakarta.nosql.Id;

@Entity
public class Room {

    @Id
    private String id;

    @Column
    private String buildingId;

    @Column
    private String roomNumber;

    @Column
    private int floor;

    public Room() {}

    public Room(String id, String buildingId, String roomNumber, int floor) {
        this.id = id;
        this.buildingId = buildingId;
        this.roomNumber = roomNumber;
        this.floor = floor;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getBuildingId() { return buildingId; }
    public void setBuildingId(String buildingId) { this.buildingId = buildingId; }

    public String getRoomNumber() { return roomNumber; }
    public void setRoomNumber(String roomNumber) { this.roomNumber = roomNumber; }

    public int getFloor() { return floor; }
    public void setFloor(int floor) { this.floor = floor; }
}
