package com.smartbuilding.entities;

import jakarta.nosql.Column;
import jakarta.nosql.Entity;
import jakarta.nosql.Id;

@Entity
public class Building {

    @Id
    private String id;

    @Column
    private String name;

    @Column
    private String address;

    @Column
    private int floors;

    public Building() {}

    public Building(String id, String name, String address, int floors) {
        this.id = id;
        this.name = name;
        this.address = address;
        this.floors = floors;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }

    public int getFloors() { return floors; }
    public void setFloors(int floors) { this.floors = floors; }
}
