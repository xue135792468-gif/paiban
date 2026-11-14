package com.paiban.notification.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Immutable post-commit delivery snapshot. It contains no JPA entity, SecurityContext
 * or mutable caller-owned collection.
 */
public record NotificationDelivery(
        String templateCode,
        List<Target> targets,
        Map<String, Object> variables,
        String title,
        String content,
        String link) {

    public NotificationDelivery {
        targets = List.copyOf(new LinkedHashSet<>(targets));
        variables = Map.copyOf(variables);
    }

    public enum TargetKind {
        USER,
        TEACHER
    }

    public record Target(TargetKind kind, Long id) {

        public Target {
            if (kind == null || id == null) {
                throw new IllegalArgumentException("通知目标必须包含类型和 ID");
            }
        }

        public static Target user(Long userId) {
            return new Target(TargetKind.USER, userId);
        }

        public static Target teacher(Long teacherId) {
            return new Target(TargetKind.TEACHER, teacherId);
        }
    }
}
