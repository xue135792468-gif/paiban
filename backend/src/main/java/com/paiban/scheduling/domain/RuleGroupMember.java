package com.paiban.scheduling.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One member of a {@link RuleGroup}. Exactly one of {@code teacherId}, {@code courseId}
 * or the slot pair {@code dayOfWeek}/{@code periodNo} is filled; the service keeps the
 * column set consistent with the group type.
 *
 * <p>Time members use the coordinate instead of a period id: a timetable edit deletes
 * removed periods but keeps surviving ones, and cross-semester copies map by
 * weekday+period number, so the coordinate is the stable identity.
 */
@Entity
@Table(name = "rule_group_member")
@Getter
@Setter
@NoArgsConstructor
public class RuleGroupMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "group_id", nullable = false)
    private Long groupId;

    /** Portable dedup key: {@code T:<teacherId>}, {@code C:<courseId>}, {@code S:<day>-<period>}. */
    @Column(name = "member_key", nullable = false, length = 48)
    private String memberKey;

    @Column(name = "teacher_id")
    private Long teacherId;

    @Column(name = "course_id")
    private Long courseId;

    @Column(name = "day_of_week")
    private Integer dayOfWeek;

    @Column(name = "period_no")
    private Integer periodNo;
}
