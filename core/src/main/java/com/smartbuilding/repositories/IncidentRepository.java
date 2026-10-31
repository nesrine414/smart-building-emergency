package com.smartbuilding.repositories;

import com.smartbuilding.entities.Incident;
import jakarta.data.repository.CrudRepository;
import jakarta.data.repository.Repository;

@Repository
public interface IncidentRepository extends CrudRepository<Incident, String> {
}
