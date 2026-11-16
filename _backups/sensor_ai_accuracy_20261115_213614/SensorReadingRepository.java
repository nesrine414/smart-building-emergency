package com.smartbuilding.repositories;

import com.smartbuilding.entities.SensorReading;
import jakarta.data.repository.CrudRepository;
import jakarta.data.repository.OrderBy;
import jakarta.data.repository.Repository;
import java.util.List;
import java.util.stream.Stream;

@Repository
public interface SensorReadingRepository extends CrudRepository<SensorReading, String> {

    @OrderBy(value = "timestamp", descending = true)
    Stream<SensorReading> findAll();

    @OrderBy(value = "timestamp", descending = true)
    List<SensorReading> findBySensorId(String sensorId);
}