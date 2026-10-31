package com.smartbuilding.repositories;

import com.smartbuilding.entities.Sensor;
import jakarta.data.repository.CrudRepository;
import jakarta.data.repository.Repository;

@Repository
public interface SensorRepository extends CrudRepository<Sensor, String> {
}
