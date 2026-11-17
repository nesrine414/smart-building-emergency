package com.smartbuilding.repositories;

import com.smartbuilding.entities.User;
import jakarta.data.repository.CrudRepository;
import jakarta.data.repository.Repository;
import java.util.Optional;

@Repository
public interface UserRepository extends CrudRepository<User, String> {
    Optional<User> findByUsername(String username);
}