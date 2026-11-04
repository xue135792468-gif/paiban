package com.paiban.identity.domain;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByUsername(String username);

    boolean existsByUsername(String username);

    Optional<AppUser> findByTeacherId(Long teacherId);

    List<AppUser> findAllByTeacherId(Long teacherId);

    List<AppUser> findAllByRolesCode(String roleCode);

    /**
     * Locks every row holding the role for the rest of the transaction. The
     * "last enabled administrator" check must count against a frozen set; otherwise two
     * concurrent disables each see the other admin as still enabled and both succeed.
     * The sub-select keeps the {@code FOR UPDATE} on {@code app_user} only.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from AppUser u where u.id in "
            + "(select holder.id from AppUser holder join holder.roles role where role.code = :roleCode) "
            + "order by u.id")
    List<AppUser> findRoleHoldersForUpdate(@Param("roleCode") String roleCode);
}
