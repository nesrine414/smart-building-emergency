package com.smartbuilding.entities;

import jakarta.nosql.Column;
import jakarta.nosql.Entity;
import jakarta.nosql.Id;

@Entity
public class PushSubscription {

    @Id
    private String endpoint; // L'URL sert d'identifiant unique

    @Column
    private String p256dh;

    @Column
    private String auth;

    @Column
    private String buildingId;

    @Column
    private int floor;

    @Column
    private String roomId;

    public PushSubscription() {}

    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }

    public String getP256dh() { return p256dh; }
    public void setP256dh(String p256dh) { this.p256dh = p256dh; }

    public String getAuth() { return auth; }
    public void setAuth(String auth) { this.auth = auth; }

    public String getBuildingId() { return buildingId; }
    public void setBuildingId(String buildingId) { this.buildingId = buildingId; }

    public int getFloor() { return floor; }
    public void setFloor(int floor) { this.floor = floor; }

    public String getRoomId() { return roomId; }
    public void setRoomId(String roomId) { this.roomId = roomId; }
}