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
}
