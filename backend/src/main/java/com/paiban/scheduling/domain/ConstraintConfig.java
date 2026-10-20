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

@Entity
@Table(name = "constraint_config")
@Getter
@Setter
@NoArgsConstructor
public class ConstraintConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "semester_id", nullable = false)
    private Long semesterId;

    @Column(nullable = false, length = 64)
    private String type;

    @Column(name = "params_json", length = 4000)
    private String paramsJson;

    @Column(nullable = false)
    private int weight = 50;

    @Column(nullable = false)
    private boolean hard;

    @Column(nullable = false)
    private boolean enabled = true;
}
