package com.paiban.timetable.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "timetable_publish")
@Getter
@Setter
@NoArgsConstructor
public class TimetablePublish {

    /**
     * The semester's current baseline. At most one row per semester carries this status:
     * {@code TimetablePublishService#publish} demotes earlier ACTIVE rows to WITHDRAWN in
     * the same transaction, because H2 and Postgres disagree on partial unique indexes.
     */
    public static final String ACTIVE = "ACTIVE";
    /** Withdrawn back to a draft; the row and its timestamps stay as history. */
    public static final String WITHDRAWN = "WITHDRAWN";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "semester_id", nullable = false)
    private Long semesterId;

    @Column(name = "draft_id", nullable = false)
    private Long draftId;

    @Column(name = "published_by", length = 64)
    private String publishedBy;

    @Column(name = "published_at", nullable = false)
    private Instant publishedAt = Instant.now();

    @Column(nullable = false, length = 16)
    private String status = ACTIVE;

    @Column(name = "withdrawn_at")
    private Instant withdrawnAt;

    @Column(name = "withdrawn_by", length = 64)
    private String withdrawnBy;
}
