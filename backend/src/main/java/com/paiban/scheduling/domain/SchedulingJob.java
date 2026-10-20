package com.paiban.scheduling.domain;

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
@Table(name = "scheduling_job")
@Getter
@Setter
@NoArgsConstructor
public class SchedulingJob {

    public static final String PENDING = "PENDING";
    public static final String RUNNING = "RUNNING";
    public static final String COMPLETED = "COMPLETED";
    public static final String FAILED = "FAILED";
    public static final String CANCELLED = "CANCELLED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "semester_id", nullable = false)
    private Long semesterId;

    @Column(nullable = false, length = 20)
    private String status = PENDING;

    @Column(length = 50)
    private String score;

    @Column(nullable = false)
    private int progress;

    @Column(name = "elapsed_ms", nullable = false)
    private long elapsedMs;

    @Column(name = "error_message", length = 2000)
    private String errorMessage;

    @Column(name = "draft_id")
    private Long draftId;

    @Column(name = "draft_name", length = 100)
    private String draftName;

    @Column(name = "solve_seconds", nullable = false)
    private int solveSeconds = 60;

    @Column(name = "created_by", length = 64)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;
}
