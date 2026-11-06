package com.paiban.scheduling.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A user-managed group of teachers, courses or timetable slots (ticket 04).
 *
 * <p>A rule stores the group id and the snapshot expands it into concrete members,
 * so adding or removing a member changes every referencing rule without resaving it.
 * The group itself never takes part in evaluation.
 *
 * <p>{@code liveKey} is the portable stand-in for a partial unique index: the service
 * keeps it non-null only while the group is alive, so two live groups of one type can
 * never share a name while a deleted name is immediately reusable (H2 2.3 has no
 * {@code CREATE UNIQUE INDEX ... WHERE}).
 */
@Entity
@Table(name = "rule_group")
@Getter
@Setter
@NoArgsConstructor
public class RuleGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "group_type", nullable = false, length = 16)
    private RuleGroupType groupType;

    @Column(nullable = false, length = 64)
    private String name;

    /** Only a {@code TIME} group belongs to a semester; teacher/course groups are global. */
    @Column(name = "semester_id")
    private Long semesterId;

    /** Unique name key of a live group: {@code TYPE:name} or {@code TIME:<semesterId>:name}. */
    @Column(name = "live_key", length = 160)
    private String liveKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Soft delete (7-day recycle bin); members are not soft-deleted. */
    @Column(name = "deleted_at")
    private Instant deletedAt;
}
