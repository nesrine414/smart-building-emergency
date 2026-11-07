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

    @Column
    private int x;

    @Column
    private int y;

    @Column
    private int width;

    @Column
    private int height;

    @Column
    private String exit;

    public Room() {}

    public Room(String id, String buildingId, String roomNumber, int floor, int x, int y, int width, int height, String exit) {
        this.id = id;
        this.buildingId = buildingId;
        this.roomNumber = roomNumber;
        this.floor = floor;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.exit = exit;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getBuildingId() { return buildingId; }
    public void setBuildingId(String buildingId) { this.buildingId = buildingId; }

    public String getRoomNumber() { return roomNumber; }
    public void setRoomNumber(String roomNumber) { this.roomNumber = roomNumber; }

    public int getFloor() { return floor; }
    public void setFloor(int floor) { this.floor = floor; }

    public int getX() { return x; }
    public void setX(int x) { this.x = x; }

    public int getY() { return y; }
    public void setY(int y) { this.y = y; }

    public int getWidth() { return width; }
    public void setWidth(int width) { this.width = width; }

    public int getHeight() { return height; }
    public void setHeight(int height) { this.height = height; }

    public String getExit() { return exit; }
    public void setExit(String exit) { this.exit = exit; }
}