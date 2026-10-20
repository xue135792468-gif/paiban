package com.paiban.foundation.domain;

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
@Table(name = "course")
@Getter
@Setter
@NoArgsConstructor
public class Course {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 100)
    private String subject;

    @Column(name = "allow_consecutive", nullable = false)
    private boolean allowConsecutive;

    @Column(name = "require_outdoor", nullable = false)
    private boolean requireOutdoor;

    @Column(name = "required_room_type", length = 50)
    private String requiredRoomType;

    @Column(length = 20)
    private String color;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
