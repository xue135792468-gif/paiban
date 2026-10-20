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
@Table(name = "room")
@Getter
@Setter
@NoArgsConstructor
public class Room {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false)
    private int capacity = 50;

    /** NORMAL / LAB / PLAYGROUND / MULTIMEDIA / OTHER */
    @Column(nullable = false, length = 50)
    private String type = "NORMAL";

    @Column(length = 100)
    private String building;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
