package com.paiban.scheduling.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The one version every effective-configuration write advances (ticket 14/07).
 *
 * <p>{@code version} is the number a client sends back as its base. It is advanced by a
 * conditional update ({@code WHERE version = :base}); two writers that read the same
 * version cannot both succeed, and the loser rolls back with zero partial writes instead
 * of silently overwriting the other. The first write inserts revision 1, so a client
 * that loaded "no configuration yet" still detects that another writer got there first.
 */
@Entity
@Table(name = "constraint_config_state")
@Getter
@Setter
@NoArgsConstructor
public class ConstraintConfigState {

    @Id
    @Column(name = "semester_id", nullable = false)
    private Long semesterId;

    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
