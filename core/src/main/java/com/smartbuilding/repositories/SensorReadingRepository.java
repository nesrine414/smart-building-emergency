package com.smartbuilding.repositories;

import com.smartbuilding.entities.SensorReading;
import jakarta.data.repository.CrudRepository;
import jakarta.data.repository.Repository;

@Repository
public interface SensorReadingRepository extends CrudRepository<SensorReading, String> {
}
