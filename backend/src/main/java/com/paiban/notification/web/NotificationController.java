package com.paiban.notification.web;

import com.paiban.identity.domain.Permissions;
import com.paiban.notification.service.NotificationService;
import com.paiban.notification.web.NotificationDtos.NotificationDto;
import com.paiban.notification.web.NotificationDtos.NotificationPage;
import com.paiban.notification.web.NotificationDtos.UnreadCount;
import java.security.Principal;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/notifications")
@PreAuthorize("hasAuthority('" + Permissions.NOTIFICATION_VIEW + "')")
public class NotificationController {

    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    @GetMapping
    public NotificationPage page(
            Principal principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.page(principal.getName(), page, size);
    }

    @GetMapping("/recent")
    public List<NotificationDto> recent(Principal principal) {
        return service.recent(principal.getName());
    }

    @GetMapping("/unread-count")
    public UnreadCount unreadCount(Principal principal) {
        return new UnreadCount(service.unreadCount(principal.getName()));
    }

    @PostMapping("/{id}/read")
    public void markRead(@PathVariable Long id, Principal principal) {
        service.markRead(principal.getName(), id);
    }

    @PostMapping("/read-all")
    public void markAllRead(Principal principal) {
        service.markAllRead(principal.getName());
    }
}
