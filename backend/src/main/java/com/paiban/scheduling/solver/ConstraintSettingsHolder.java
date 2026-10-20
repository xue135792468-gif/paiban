package com.paiban.scheduling.solver;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Carries the active constraint settings into the ConstraintProvider, which is
 * instantiated by Timefold without Spring injection. Only one solve runs at a
 * time per deployment, so a single holder is sufficient; it is cleared when the
 * solve finishes.
 */
public final class ConstraintSettingsHolder {

    private static final AtomicReference<ConstraintSettings> CURRENT =
            new AtomicReference<>(ConstraintSettings.defaults());

    private ConstraintSettingsHolder() {}

    public static ConstraintSettings get() {
        return CURRENT.get();
    }

    public static void set(ConstraintSettings settings) {
        CURRENT.set(settings);
    }

    public static void reset() {
        CURRENT.set(ConstraintSettings.defaults());
    }
}
