package com.paiban.onboarding.web;

import com.paiban.onboarding.service.OnboardingService;
import com.paiban.onboarding.web.OnboardingDtos.ProgressDto;
import com.paiban.onboarding.web.OnboardingDtos.SaveProgressRequest;
import com.paiban.onboarding.web.OnboardingDtos.StatusDto;
import java.security.Principal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/onboarding")
public class OnboardingController {

    private final OnboardingService service;

    public OnboardingController(OnboardingService service) {
        this.service = service;
    }

    @GetMapping
    public ProgressDto get(Principal principal) {
        return service.get(principal.getName());
    }

    @GetMapping("/status")
    public StatusDto status(Principal principal) {
        return service.status(principal.getName());
    }

    @PutMapping
    public ProgressDto save(Principal principal, @RequestBody SaveProgressRequest request) {
        return service.save(principal.getName(), request);
    }

    @PostMapping("/reset")
    public void reset(Principal principal) {
        service.reset(principal.getName());
    }
}
