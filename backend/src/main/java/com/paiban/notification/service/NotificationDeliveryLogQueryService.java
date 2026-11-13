package com.paiban.notification.service;

import com.paiban.foundation.domain.Teacher;
import com.paiban.foundation.domain.TeacherRepository;
import com.paiban.identity.domain.AppUser;
import com.paiban.identity.domain.AppUserRepository;
import com.paiban.notification.domain.NotificationLog;
import com.paiban.notification.domain.NotificationLogRepository;
import com.paiban.notification.web.NotificationDeliveryLogDtos.NotificationDeliveryLogItem;
import com.paiban.notification.web.NotificationDeliveryLogDtos.NotificationDeliveryLogPage;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationDeliveryLogQueryService {

    private static final String SAFE_FAILURE_MESSAGE = "发送失败，请联系管理员";
    private static final Pattern USER_MARKER = Pattern.compile("user:(\\d+)");
    private static final Pattern TEACHER_MARKER = Pattern.compile("teacher:(\\d+)");
    private static final Pattern LOCAL_RECIPIENTS =
            Pattern.compile("\\[\\s*\\d+(?:\\s*,\\s*\\d+)*\\s*]");

    private final NotificationLogRepository logs;
    private final TeacherRepository teachers;
    private final AppUserRepository users;

    public NotificationDeliveryLogQueryService(
            NotificationLogRepository logs,
            TeacherRepository teachers,
            AppUserRepository users) {
        this.logs = logs;
        this.teachers = teachers;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public NotificationDeliveryLogPage page(int page, int size) {
        long total = logs.count();
        long offset = (long) page * size;
        if (offset >= total) {
            return new NotificationDeliveryLogPage(List.of(), total, page, size);
        }
        Page<NotificationLog> found = logs.findAll(PageRequest.of(
                page,
                size,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))));
        List<NotificationDeliveryLogItem> items = found.getContent().stream()
                .map(this::toItem)
                .toList();
        return new NotificationDeliveryLogPage(
                items,
                found.getTotalElements(),
                found.getNumber(),
                found.getSize());
    }

    private NotificationDeliveryLogItem toItem(NotificationLog log) {
        String recipient = log.getRecipient() == null ? "" : log.getRecipient();
        return new NotificationDeliveryLogItem(
                log.getId(),
                normalized(log.getChannel()),
                normalized(log.getPlatform()),
                recipient,
                recipientLabel(log, recipient),
                normalized(log.getTemplateCode()),
                normalized(log.getContent()),
                log.isSuccess(),
                log.getCreatedAt().toString(),
                log.isSuccess() ? null : SAFE_FAILURE_MESSAGE);
    }

    private String recipientLabel(NotificationLog log, String recipient) {
        if (isInternalFailure(log)) {
            Matcher userMarker = USER_MARKER.matcher(recipient);
            if (userMarker.matches()) {
                Long userId = positiveId(userMarker.group(1));
                if (userId != null) {
                    AppUser user = users.findById(userId).orElse(null);
                    return user == null ? recipient : user.getDisplayName();
                }
            }

            Matcher teacherMarker = TEACHER_MARKER.matcher(recipient);
            if (teacherMarker.matches()) {
                Long teacherId = positiveId(teacherMarker.group(1));
                if (teacherId != null) {
                    Teacher teacher = teachers.findById(teacherId).orElse(null);
                    return teacher == null ? recipient : teacher.getName();
                }
            }
        }

        if (isLocal(log) && LOCAL_RECIPIENTS.matcher(recipient).matches()) {
            return localRecipientLabel(recipient);
        }

        if (!isDingTalk(log)) {
            return recipient;
        }
        List<Teacher> matched = teachers.findAllByDingtalkUserId(recipient);
        return matched.size() == 1 ? matched.getFirst().getName() : recipient;
    }

    private String localRecipientLabel(String recipient) {
        List<Long> ids = Arrays.stream(recipient.substring(1, recipient.length() - 1).split(","))
                .map(String::trim)
                .map(NotificationDeliveryLogQueryService::positiveId)
                .toList();
        Set<String> names = new LinkedHashSet<>();
        for (Long id : ids) {
            if (id == null) {
                return recipient;
            }
            AppUser user = users.findById(id).orElse(null);
            if (user == null) {
                return recipient;
            }
            names.add(user.getDisplayName());
        }
        return names.isEmpty() ? recipient : String.join("、", names);
    }

    private static boolean isLocal(NotificationLog log) {
        return equalsIgnoreCase(log.getChannel(), "local")
                && (log.getPlatform() == null || equalsIgnoreCase(log.getPlatform(), "system"));
    }

    private static boolean isDingTalk(NotificationLog log) {
        return equalsIgnoreCase(log.getChannel(), "dingtalk");
    }

    private static boolean isInternalFailure(NotificationLog log) {
        return equalsIgnoreCase(log.getChannel(), "notification")
                && (log.getPlatform() == null || equalsIgnoreCase(log.getPlatform(), "system"));
    }

    private static boolean equalsIgnoreCase(String actual, String expected) {
        return actual != null && actual.equalsIgnoreCase(expected);
    }

    private static Long positiveId(String value) {
        try {
            long id = Long.parseLong(value);
            return id > 0 ? id : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value;
    }
}
