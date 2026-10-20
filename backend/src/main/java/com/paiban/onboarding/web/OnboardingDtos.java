package com.paiban.onboarding.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class OnboardingDtos {

    private OnboardingDtos() {}

    public record ProgressDto(int currentStep, List<Integer> completedSteps, Map<String, Object> data,
                              boolean skipped, Instant updatedAt) {}

    public record SaveProgressRequest(Integer currentStep, List<Integer> completedSteps,
                                      Map<String, Object> data, Boolean skipped) {}

    public record HintDto(String key, String label, String message, String route, boolean done) {}

    public record StatusDto(boolean showWizard, boolean skipped, boolean hasProgress,
                            List<HintDto> hints) {}
}
