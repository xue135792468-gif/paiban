package com.paiban.notification.web;

import com.paiban.common.web.ApiException;
import com.paiban.identity.domain.Permissions;
import com.paiban.notification.service.NotificationDeliveryLogQueryService;
import com.paiban.notification.web.NotificationDeliveryLogDtos.NotificationDeliveryLogPage;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class NotificationDeliveryLogController {

    private final NotificationDeliveryLogQueryService queries;

    public NotificationDeliveryLogController(NotificationDeliveryLogQueryService queries) {
        this.queries = queries;
    }

    @GetMapping("/notification-delivery-logs")
    @PreAuthorize("hasAuthority('" + Permissions.SUBSTITUTION_VIEW + "')")
    public NotificationDeliveryLogPage list(
            @RequestParam(name = "page", defaultValue = "0") String page,
            @RequestParam(name = "size", defaultValue = "20") String size) {
        return queries.page(
                boundedInt("page", page, 0, Integer.MAX_VALUE),
                boundedInt("size", size, 1, 100));
    }

    private static int boundedInt(String name, String raw, int minimum, int maximum) {
        final int value;
        try {
            value = Integer.parseInt(raw);
        } catch (NumberFormatException ex) {
            throw ApiException.badRequest(name + " 必须是整数");
        }
        if (value < minimum || value > maximum) {
            throw ApiException.badRequest(name + " 超出允许范围");
        }
        return value;
    }
}
