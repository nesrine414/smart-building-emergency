package com.smartbuilding.repositories;

import com.smartbuilding.entities.AiInteraction;
import jakarta.data.repository.CrudRepository;
import jakarta.data.repository.OrderBy;
import jakarta.data.repository.Repository;
import java.util.stream.Stream;

@Repository
public interface AiInteractionRepository extends CrudRepository<AiInteraction, String> {

    @OrderBy(value = "timestamp", descending = true)
    Stream<AiInteraction> findAll();
}