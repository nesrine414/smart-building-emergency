package com.smartbuilding.repositories;

import com.smartbuilding.entities.Building;
import jakarta.data.repository.CrudRepository;
import jakarta.data.repository.Repository;

@Repository
public interface BuildingRepository extends CrudRepository<Building, String> {
}
