package com.smartbuilding.repositories;

import com.smartbuilding.entities.Sensor;
import jakarta.data.repository.CrudRepository;
import jakarta.data.repository.Repository;
import java.util.List;
import java.util.stream.Stream;

@Repository
public interface SensorRepository extends CrudRepository<Sensor, String> {
    Stream<Sensor> findAll();
    List<Sensor> findByRoomId(String roomId);
}