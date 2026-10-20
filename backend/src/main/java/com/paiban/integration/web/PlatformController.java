package com.paiban.integration.web;

import com.paiban.identity.domain.Permissions;
import com.paiban.integration.platform.PlatformSwitchService;
import com.paiban.integration.web.PlatformDtos.PlatformStatusDto;
import com.paiban.integration.web.PlatformDtos.SwitchPlatformRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** IM platform status and one-click switch (ticket 05). */
@RestController
@RequestMapping("/api/integrations/platform")
public class PlatformController {

    private final PlatformSwitchService platformSwitch;

    public PlatformController(PlatformSwitchService platformSwitch) {
        this.platformSwitch = platformSwitch;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + Permissions.INTEGRATION_MANAGE + "')")
    public PlatformStatusDto status() {
        return platformSwitch.status();
    }

    @PutMapping
    @PreAuthorize("hasAuthority('" + Permissions.INTEGRATION_MANAGE + "')")
    public PlatformStatusDto switchPlatform(@RequestBody(required = false) SwitchPlatformRequest request) {
        return platformSwitch.switchTo(request == null ? null : request.platform());
    }
}
