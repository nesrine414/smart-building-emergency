package com.smartbuilding.repositories;

import com.smartbuilding.entities.PushSubscription;
import jakarta.data.repository.CrudRepository;
import jakarta.data.repository.Repository;
import java.util.stream.Stream;

@Repository
public interface PushSubscriptionRepository extends CrudRepository<PushSubscription, String> {
    Stream<PushSubscription> findAll();
}