package com.paiban.foundation.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoomRepository extends JpaRepository<Room, Long> {

    List<Room> findAllByOrderByNameAsc();

    Optional<Room> findByName(String name);
}
