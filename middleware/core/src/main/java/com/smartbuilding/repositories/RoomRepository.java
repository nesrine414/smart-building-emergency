package com.smartbuilding.repositories;

import com.smartbuilding.entities.Room;
import jakarta.data.repository.CrudRepository;
import jakarta.data.repository.Repository;
import java.util.List;
import java.util.stream.Stream;

@Repository
public interface RoomRepository extends CrudRepository<Room, String> {
    Stream<Room> findAll();
    List<Room> findByBuildingId(String buildingId);
}