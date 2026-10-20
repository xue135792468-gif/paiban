package com.paiban.scheduling.solver.plugin;

import ai.timefold.solver.core.api.score.stream.Constraint;
import ai.timefold.solver.core.api.score.stream.ConstraintFactory;
import com.paiban.scheduling.solver.ConstraintSettings;
import java.util.List;

/**
 * Phase-1 extension point for third-party constraints (ADR-0007). Implementations are
 * discovered with {@link java.util.ServiceLoader}: put the implementation on the classpath,
 * or drop a jar exposing it into the {@code plugins/} directory.
 */
public interface CustomConstraint {

    /** Stable identifier used in logs. */
    String name();

    /** Build the constraints this plugin contributes for the current solve. */
    List<Constraint> define(ConstraintFactory factory, ConstraintSettings settings);
}
