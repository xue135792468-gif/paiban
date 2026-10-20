package com.paiban.scheduling;

import ai.timefold.solver.core.api.score.stream.Constraint;
import ai.timefold.solver.core.api.score.stream.ConstraintFactory;
import com.paiban.scheduling.solver.ConstraintSettings;
import com.paiban.scheduling.solver.plugin.CustomConstraint;
import java.util.List;

/** Test-only plugin proving the ServiceLoader path in ADR-0007 works. */
public class TestCustomConstraint implements CustomConstraint {

    @Override
    public String name() {
        return "test-plugin";
    }

    @Override
    public List<Constraint> define(ConstraintFactory factory, ConstraintSettings settings) {
        return List.of();
    }
}
