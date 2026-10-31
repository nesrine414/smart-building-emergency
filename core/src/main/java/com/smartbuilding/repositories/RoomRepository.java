package com.smartbuilding.repositories;

import com.smartbuilding.entities.Room;
import jakarta.data.repository.CrudRepository;
import jakarta.data.repository.Repository;

@Repository
public interface RoomRepository extends CrudRepository<Room, String> {
}
