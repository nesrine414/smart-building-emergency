package com.smartbuilding.repositories;

import com.smartbuilding.entities.Incident;
import jakarta.data.repository.CrudRepository;
import jakarta.data.repository.OrderBy;
import jakarta.data.repository.Repository;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

@Repository
public interface IncidentRepository extends CrudRepository<Incident, String> {

    @OrderBy(value = "timestamp", descending = true)
    Stream<Incident> findAll();

    @OrderBy(value = "timestamp", descending = true)
    List<Incident> findByStatus(String status);

    Optional<Incident> findByRoomIdAndTypeAndStatus(String roomId, String type, String status);
}