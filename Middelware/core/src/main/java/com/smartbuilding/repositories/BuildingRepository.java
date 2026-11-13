package com.smartbuilding.repositories;

import com.smartbuilding.entities.Building;
import jakarta.data.repository.CrudRepository;
import jakarta.data.repository.Repository;
import java.util.stream.Stream;

@Repository
public interface BuildingRepository extends CrudRepository<Building, String> {
    Stream<Building> findAll();
}