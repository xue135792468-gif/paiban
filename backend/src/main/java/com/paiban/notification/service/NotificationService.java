package com.paiban.notification.service;

import com.paiban.common.web.ApiException;
import com.paiban.foundation.domain.AdminClass;
import com.paiban.foundation.domain.AdminClassRepository;
import com.paiban.foundation.domain.Course;
import com.paiban.foundation.domain.CourseRepository;
import com.paiban.foundation.domain.Teacher;
import com.paiban.foundation.domain.TeacherRepository;
import com.paiban.notification.service.NotificationDelivery.Target;
import com.paiban.substitution.domain.Substitution;
import com.paiban.substitution.domain.SubstitutionRequest;
import com.paiban.substitution.service.SubstitutionMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

/**
 * Builds immutable delivery snapshots inside the business transaction. Actual delivery
 * is registered for after-commit and then handled by the dedicated notification worker.
 */
@Service
public class NotificationService {

    private final ApplicationEventPublisher events;
    private final AdminClassRepository adminClasses;
    private final CourseRepository courses;
    private final TeacherRepository teachers;
    private final TemplateEngine templateEngine;

    public NotificationService(
            ApplicationEventPublisher events,
            AdminClassRepository adminClasses,
            CourseRepository courses,
            TeacherRepository teachers,
            @Qualifier("notificationTemplateEngine") TemplateEngine templateEngine) {
        this.events = events;
        this.adminClasses = adminClasses;
        this.courses = courses;
        this.teachers = teachers;
        this.templateEngine = templateEngine;
    }

    /**
     * Legacy callers may still compile, but only active business templates are accepted.
     * Retired templates remain registered for historical rows and rollback.
     */
    public void notify(
            String templateCode,
            List<Long> recipientUserIds,
            Map<String, Object> variables,
            String link) {
        if (recipientUserIds == null || recipientUserIds.isEmpty()) {
            return;
        }
        List<Target> targets = recipientUserIds.stream()
                .filter(java.util.Objects::nonNull)
                .map(Target::user)
                .toList();
        deliverAfterCommit(templateCode, targets, variables, link);
    }

    /** Only the final substitute teacher receives a cover-effective notification. */
    public void substitutionEffective(
            SubstitutionRequest request,
            Substitution substitution,
            Map<String, Object> variables) {
        if (substitution.getSubstituteTeacherId() == null) {
            return;
        }
        deliverAfterCommit(
                NotificationTemplates.SUBSTITUTION_EFFECTIVE_SUBSTITUTE,
                List.of(Target.teacher(substitution.getSubstituteTeacherId())),
                variables,
                "/substitutions?requestId=" + request.getId());
    }

    /** One delivery per moved teacher, containing only that teacher's own moves. */
    public void swapEffective(
            SubstitutionRequest request,
            Map<Long, String> detailsByTeacher) {
        String link = "/substitutions?requestId=" + request.getId();
        String className = adminClasses.findById(request.getAdminClassId())
                .map(AdminClass::getName)
                .orElse("");
        detailsByTeacher.forEach((teacherId, details) -> deliverAfterCommit(
                NotificationTemplates.SUBSTITUTION_SWAP_EFFECTIVE,
                List.of(Target.teacher(teacherId)),
                Map.of("details", details, "className", className),
                link));
    }

    public void approvalApproved(SubstitutionRequest request, String comment) {
        deliverAfterCommit(
                NotificationTemplates.SUBSTITUTION_APPROVAL_APPROVED,
                approvalTarget(request),
                approvalVariables(request, comment),
                "/substitutions/my?requestId=" + request.getId());
    }

    public void approvalRejected(SubstitutionRequest request, String reason) {
        deliverAfterCommit(
                NotificationTemplates.SUBSTITUTION_APPROVAL_REJECTED,
                approvalTarget(request),
                approvalVariables(request, reason),
                "/substitutions/my?requestId=" + request.getId());
    }

    private List<Target> approvalTarget(SubstitutionRequest request) {
        return request.getOperatorUserId() == null
                ? List.of()
                : List.of(Target.user(request.getOperatorUserId()));
    }

    private Map<String, Object> approvalVariables(SubstitutionRequest request, String comment) {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("className", adminClasses.findById(request.getAdminClassId())
                .map(AdminClass::getName)
                .orElse(""));
        variables.put("courseName", courses.findById(request.getCourseId())
                .map(Course::getName)
                .orElse(""));
        variables.put("date", request.getEffectiveDate() == null
                ? "" : request.getEffectiveDate().toString());
        variables.put("periodLabel",
                SubstitutionMapper.label(request.getDayOfWeek(), request.getPeriodNo()));
        Long finalTeacherId = request.getFinalTeacherId() != null
                ? request.getFinalTeacherId() : request.getExpectedSubstituteTeacherId();
        variables.put("substituteTeacher", teacherName(finalTeacherId));
        variables.put("comment", comment == null ? "" : comment);
        return variables;
    }

    private String teacherName(Long teacherId) {
        return teacherId == null
                ? ""
                : teachers.findById(teacherId).map(Teacher::getName).orElse("");
    }

    private void deliverAfterCommit(
            String templateCode,
            List<Target> targets,
            Map<String, Object> variables,
            String link) {
        List<Target> distinctTargets = new ArrayList<>(new LinkedHashSet<>(targets));
        if (distinctTargets.isEmpty()) {
            return;
        }
        NotificationTemplates.Template template = NotificationTemplates.get(templateCode);
        if (template == null) {
            throw ApiException.badRequest("未知通知模板: " + templateCode);
        }
        if (!NotificationTemplates.isActive(templateCode)) {
            return;
        }
        Context context = new Context();
        context.setVariables(variables);
        String title = templateEngine.process(template.title(), context);
        String content = templateEngine.process(template.body(), context);
        events.publishEvent(new NotificationDeliveryRequested(new NotificationDelivery(
                templateCode,
                distinctTargets,
                variables,
                title,
                content,
                link)));
    }
}
