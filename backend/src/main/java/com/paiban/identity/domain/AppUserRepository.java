package com.paiban.identity.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByUsername(String username);

    boolean existsByUsername(String username);

    Optional<AppUser> findByTeacherId(Long teacherId);

    java.util.List<AppUser> findAllByTeacherId(Long teacherId);

    java.util.List<AppUser> findAllByRolesCode(String roleCode);
}
