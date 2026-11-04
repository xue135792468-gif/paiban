package com.paiban.foundation.service;

import com.paiban.common.deletion.DeleteMode;
import com.paiban.common.deletion.DeletionRestoreHandler;
import com.paiban.common.deletion.EntityType;
import com.paiban.common.deletion.RecentDeletion;
import com.paiban.common.deletion.RecentDeletionRepository;
import com.paiban.common.web.ApiException;
import com.paiban.foundation.domain.AdminClass;
import com.paiban.foundation.domain.AdminClassRepository;
import com.paiban.foundation.domain.AuditLog;
import com.paiban.foundation.domain.AuditLogRepository;
import com.paiban.foundation.domain.Course;
import com.paiban.foundation.domain.CourseRepository;
import com.paiban.foundation.domain.Grade;
import com.paiban.foundation.domain.GradeRepository;
import com.paiban.foundation.domain.Semester;
import com.paiban.foundation.domain.SemesterRepository;
import com.paiban.foundation.domain.Teacher;
import com.paiban.foundation.domain.TeacherRepository;
import com.paiban.foundation.web.LifecycleDtos.DeleteImpactDto;
import com.paiban.foundation.web.LifecycleDtos.DeleteResultDto;
import com.paiban.foundation.web.LifecycleDtos.RecentDeletionDto;
import com.paiban.foundation.web.LifecycleDtos.RestoreResultDto;
import com.paiban.scheduling.domain.Assignment;
import com.paiban.scheduling.domain.AssignmentRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * The single seam for “delete / disable / restore” of core data (ticket 03).
 *
 * <p>Deleting is always soft: the row stays, {@code deleted_at} is stamped and
 * {@code active} becomes false, so draft lessons and constraint parameters that
 * still point at the id keep resolving. A deletion is registered in
 * {@code recent_deletion} for 7 days and can be undone as a whole batch.
 *
 * <p>References decide the level: published timetable entries or substitution
 * records make the entity “disable only”; assignments/classes left behind are
 * removed in the same batch only when the caller chose CASCADE; drafts and rules
 * never block.
 */
@Service
public class LifecycleService {

    private static final Duration RETENTION = Duration.ofDays(7);

    private final TeacherRepository teachers;
    private final CourseRepository courses;
    private final GradeRepository grades;
    private final AdminClassRepository classes;
    private final SemesterRepository semesters;
    private final AssignmentRepository assignments;
    private final RecentDeletionRepository recentDeletions;
    private final AuditLogRepository auditLogs;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final List<DeletionRestoreHandler> restoreHandlers;

    public LifecycleService(
            TeacherRepository teachers,
            CourseRepository courses,
            GradeRepository grades,
            AdminClassRepository classes,
            SemesterRepository semesters,
            AssignmentRepository assignments,
            RecentDeletionRepository recentDeletions,
            AuditLogRepository auditLogs,
            JdbcTemplate jdbc,
            Clock clock,
            List<DeletionRestoreHandler> restoreHandlers) {
        this.teachers = teachers;
        this.courses = courses;
        this.grades = grades;
        this.classes = classes;
        this.semesters = semesters;
        this.assignments = assignments;
        this.recentDeletions = recentDeletions;
        this.auditLogs = auditLogs;
        this.jdbc = jdbc;
        this.clock = clock;
        this.restoreHandlers = restoreHandlers == null ? List.of() : List.copyOf(restoreHandlers);
    }

    // ---------- impact ----------

    @Transactional(readOnly = true)
    public DeleteImpactDto impact(EntityType type, Long id) {
        EntityRow row = load(type, id);
        long published = publishedRefs(type, row);
        long substitutions = substitutionRefs(type, id);
        long drafts = draftRefs(type, id);
        long assignmentRefs = assignmentRefs(type, id);
        long rules = ruleRefs(type, id);
        long cascadeAssignments = switch (type) {
            case TEACHER, COURSE, CLASS, GRADE, SEMESTER -> assignmentRefs;
            case ASSIGNMENT -> 0;
        };
        long cascadeClasses = type == EntityType.GRADE
                ? one("select count(*) from admin_class where grade_id = ? and deleted_at is null", id)
                : 0;
        boolean currentSemester = type == EntityType.SEMESTER && isCurrentSemester(id);
        boolean canDelete = published == 0 && substitutions == 0 && !currentSemester;
        boolean canDisable = !currentSemester;
        return new DeleteImpactDto(
                type.name(), id, row.label(), published, substitutions, drafts, assignmentRefs, rules,
                canDelete, canDisable, cascadeAssignments, cascadeClasses);
    }

    // ---------- delete / disable / enable ----------

    @Transactional
    public DeleteResultDto delete(EntityType type, Long id, DeleteMode mode) {
        EntityRow primary = load(type, id);
        if (primary.deleted()) {
            throw ApiException.notFound(type.label() + "不存在: " + id);
        }
        DeleteImpactDto impact = impact(type, id);
        if (!impact.canDelete()) {
            throw ApiException.conflict(canDeleteRefusal(type, id));
        }
        if (mode == DeleteMode.DELETE && (impact.cascadeAssignments() > 0 || impact.cascadeClasses() > 0)) {
            throw ApiException.conflict(cascadeRefusal(type, impact));
        }

        Instant now = Instant.now(clock);
        String batchId = UUID.randomUUID().toString();
        List<EntityRow> targets = new ArrayList<>();
        targets.add(primary);
        if (mode == DeleteMode.CASCADE) {
            targets.addAll(cascade(type, id));
        }

        List<Boolean> wasActive = new ArrayList<>();
        for (EntityRow target : targets) {
            wasActive.add(isActive(target.type(), target.id()));
            markDeleted(target.type(), target.id(), now);
        }
        for (int index = 0; index < targets.size(); index++) {
            EntityRow target = targets.get(index);
            RecentDeletion deletion = new RecentDeletion();
            deletion.setBatchId(batchId);
            deletion.setEntityType(target.type().name());
            deletion.setEntityId(target.id());
            deletion.setEntityLabel(target.label());
            deletion.setPrimaryFlag(index == 0);
            deletion.setWasActive(wasActive.get(index));
            deletion.setDeletedBy(actor());
            deletion.setDeletedAt(now);
            deletion.setExpiresAt(now.plus(RETENTION));
            recentDeletions.save(deletion);
        }

        String detail = "删除" + type.label() + "「" + primary.label() + "」"
                + (targets.size() > 1 ? "，级联 " + (targets.size() - 1) + " 项" : "")
                + "（批次 " + batchId + "）";
        audit("DELETE_" + type.name(), type.name(), detail, targets.size());
        return new DeleteResultDto(batchId, targets.size());
    }

    @Transactional
    public void disable(EntityType type, Long id) {
        EntityRow row = load(type, id);
        if (row.deleted()) {
            throw ApiException.notFound(type.label() + "不存在: " + id);
        }
        if (type == EntityType.SEMESTER && isCurrentSemester(id)) {
            throw ApiException.conflict("不能停用当前学期，请先切换到其他学期");
        }
        setActive(type, id, false);
        audit("DISABLE_" + type.name(), type.name(), "停用" + type.label() + "「" + row.label() + "」", 1);
    }

    @Transactional
    public void enable(EntityType type, Long id) {
        EntityRow row = load(type, id);
        if (row.deleted()) {
            throw ApiException.conflict("该" + type.label() + "已删除，请先在“最近删除”中恢复");
        }
        setActive(type, id, true);
        audit("ENABLE_" + type.name(), type.name(), "启用" + type.label() + "「" + row.label() + "」", 1);
    }

    // ---------- recent deletions ----------

    @Transactional(readOnly = true)
    public List<RecentDeletionDto> recent(boolean includeClosed) {
        Instant now = Instant.now(clock);
        List<RecentDeletion> rows = includeClosed
                ? recentDeletions.findAllByOrderByDeletedAtDesc()
                : recentDeletions.findByRestoredAtIsNullAndExpiresAtAfterOrderByDeletedAtDesc(now);
        return rows.stream().map(row -> toDto(row, now)).toList();
    }

    @Transactional
    public RestoreResultDto restore(String batchId, String actor) {
        List<RecentDeletion> rows = recentDeletions.findByBatchIdOrderByIdAsc(batchId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("删除批次不存在: " + batchId);
        }
        Instant now = Instant.now(clock);
        boolean alreadyClosed = rows.stream().anyMatch(row -> row.getRestoredAt() != null);
        boolean expired = rows.stream().anyMatch(row -> now.isAfter(row.getExpiresAt()));
        if (alreadyClosed || expired) {
            throw new ApiException(HttpStatus.GONE, "该批次已恢复或已超过 7 天，无法撤销");
        }
        for (RecentDeletion row : rows) {
            if (!restoreWithHandler(row.getEntityType(), row.getEntityId())) {
                restoreOne(EntityType.fromStored(row.getEntityType()), row.getEntityId(), row.getWasActive());
            }
            row.setRestoredAt(now);
            row.setRestoredBy(StringUtils.hasText(actor) ? actor : actor());
        }
        RecentDeletion primary = rows.stream()
                .filter(RecentDeletion::isPrimaryFlag)
                .findFirst()
                .orElse(rows.get(0));
        String detail = "恢复" + labelOf(primary.getEntityType()) + "「" + primary.getEntityLabel() + "」"
                + (rows.size() > 1 ? "，一并恢复 " + (rows.size() - 1) + " 项" : "")
                + "（批次 " + batchId + "）";
        audit("RESTORE_" + primary.getEntityType(), primary.getEntityType(), detail, rows.size());
        return new RestoreResultDto(batchId, rows.size());
    }

    /**
     * Removes expired recycle markers. The business rows are intentionally kept:
     * physical cleanup is a later ticket, and drafts/rules may still resolve ids.
     */
    @Transactional
    public int purgeExpired(Instant now) {
        return (int) recentDeletions.deleteByRestoredAtIsNullAndExpiresAtBefore(now);
    }

    // ---------- reference counting ----------

    private long publishedRefs(EntityType type, EntityRow row) {
        return switch (type) {
            case TEACHER -> one("select count(*) from timetable_entry where teacher_id = ?", row.id());
            case CLASS -> one("select count(*) from timetable_entry where admin_class_id = ?", row.id());
            case COURSE -> one("select count(*) from timetable_entry where course_id = ?", row.id());
            case GRADE -> one("select count(*) from timetable_entry e "
                    + "join admin_class c on c.id = e.admin_class_id where c.grade_id = ?", row.id());
            case SEMESTER -> one("select count(*) from timetable_entry where semester_id = ?", row.id());
            case ASSIGNMENT -> {
                Assignment assignment = requireAssignment(row.id());
                yield one("select count(*) from timetable_entry where semester_id = ? "
                                + "and admin_class_id = ? and course_id = ? and teacher_id = ?",
                        assignment.getSemesterId(), assignment.getAdminClassId(),
                        assignment.getCourseId(), assignment.getTeacherId());
            }
        };
    }

    private long substitutionRefs(EntityType type, Long id) {
        return switch (type) {
            case TEACHER -> one("select count(*) from substitution "
                            + "where original_teacher_id = ? or substitute_teacher_id = ?",
                    id, id)
                    + one("select count(*) from substitution_request where original_teacher_id = ? "
                            + "or requester_teacher_id = ? or expected_substitute_teacher_id = ? "
                            + "or previous_teacher_id = ? or final_teacher_id = ?",
                    id, id, id, id, id);
            case CLASS -> one("select count(*) from substitution_request where admin_class_id = ?", id)
                    + one("select count(*) from substitution s "
                            + "join timetable_entry e on e.id = s.entry_id where e.admin_class_id = ?", id);
            case COURSE -> one("select count(*) from substitution_request where course_id = ?", id)
                    + one("select count(*) from substitution s "
                            + "join timetable_entry e on e.id = s.entry_id where e.course_id = ?", id);
            case GRADE -> one("select count(*) from substitution_request r "
                            + "join admin_class c on c.id = r.admin_class_id where c.grade_id = ?", id)
                    + one("select count(*) from substitution s "
                            + "join timetable_entry e on e.id = s.entry_id "
                            + "join admin_class c on c.id = e.admin_class_id where c.grade_id = ?", id);
            case SEMESTER -> one("select count(*) from substitution where semester_id = ?", id)
                    + one("select count(*) from substitution_request where semester_id = ?", id);
            case ASSIGNMENT -> 0;
        };
    }

    private long draftRefs(EntityType type, Long id) {
        return switch (type) {
            case TEACHER -> one("select count(*) from draft_lesson where teacher_id = ?", id);
            case CLASS -> one("select count(*) from draft_lesson where admin_class_id = ?", id);
            case COURSE -> one("select count(*) from draft_lesson where course_id = ?", id);
            case GRADE -> one("select count(*) from draft_lesson dl "
                    + "join admin_class c on c.id = dl.admin_class_id where c.grade_id = ?", id);
            case SEMESTER -> one("select count(*) from draft_lesson dl "
                    + "join timetable_draft d on d.id = dl.draft_id where d.semester_id = ?", id);
            case ASSIGNMENT -> one("select count(*) from draft_lesson where assignment_id = ?", id);
        };
    }

    private long assignmentRefs(EntityType type, Long id) {
        return switch (type) {
            case TEACHER -> one("select count(*) from assignment "
                    + "where teacher_id = ? and deleted_at is null", id);
            case COURSE -> one("select count(*) from assignment "
                    + "where course_id = ? and deleted_at is null", id);
            case CLASS -> one("select count(*) from assignment "
                    + "where admin_class_id = ? and deleted_at is null", id);
            case GRADE -> one("select count(*) from assignment a "
                    + "join admin_class c on c.id = a.admin_class_id "
                    + "where c.grade_id = ? and a.deleted_at is null", id);
            case SEMESTER -> one("select count(*) from assignment "
                    + "where semester_id = ? and deleted_at is null", id);
            case ASSIGNMENT -> 0;
        };
    }

    private long ruleRefs(EntityType type, Long id) {
        String key = switch (type) {
            case TEACHER -> "teacherId";
            case CLASS -> "classId";
            case COURSE -> "courseId";
            case GRADE, SEMESTER, ASSIGNMENT -> null;
        };
        if (key == null) {
            return 0;
        }
        String plain = "\"" + key + "\":" + id;
        String spaced = "\"" + key + "\": " + id;
        return one("select count(*) from constraint_entry where params_json like ? "
                        + "or params_json like ? or params_json like ? or params_json like ?",
                "%" + plain + ",%", "%" + plain + "}%",
                "%" + spaced + ",%", "%" + spaced + "}%");
    }

    private long one(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    // ---------- cascade ----------

    private List<EntityRow> cascade(EntityType type, Long id) {
        return switch (type) {
            case TEACHER -> assignmentRows(assignments.findByTeacherIdAndDeletedAtIsNull(id));
            case COURSE -> assignmentRows(assignments.findByCourseIdAndDeletedAtIsNull(id));
            case CLASS -> assignmentRows(assignments.findByAdminClassIdAndDeletedAtIsNull(id));
            case GRADE -> {
                List<EntityRow> rows = new ArrayList<>();
                for (AdminClass adminClass : classes.findByGradeIdAndDeletedAtIsNull(id)) {
                    rows.add(new EntityRow(EntityType.CLASS, adminClass.getId(), adminClass.getName(), false));
                    rows.addAll(assignmentRows(
                            assignments.findByAdminClassIdAndDeletedAtIsNull(adminClass.getId())));
                }
                yield rows;
            }
            case SEMESTER ->
                // Semester cascade removes every undeleted assignment, including disabled
                // ones (the recycle-bin batch must bring the whole semester back together).
                assignmentRows(assignments.findBySemesterIdForList(id));
            case ASSIGNMENT -> List.of();
        };
    }

    private List<EntityRow> assignmentRows(List<Assignment> rows) {
        return rows.stream()
                .map(assignment -> new EntityRow(
                        EntityType.ASSIGNMENT, assignment.getId(), assignmentLabel(assignment), false))
                .toList();
    }

    private String assignmentLabel(Assignment assignment) {
        String className = classes.findById(assignment.getAdminClassId())
                .map(AdminClass::getName).orElse("#" + assignment.getAdminClassId());
        String courseName = courses.findById(assignment.getCourseId())
                .map(Course::getName).orElse("#" + assignment.getCourseId());
        String teacherName = teachers.findById(assignment.getTeacherId())
                .map(Teacher::getName).orElse(null);
        return className + " / " + courseName + (teacherName == null ? "" : " / " + teacherName);
    }

    // ---------- entity access ----------

    private EntityRow load(EntityType type, Long id) {
        return switch (type) {
            case TEACHER -> teachers.findById(id)
                    .map(row -> new EntityRow(type, id, row.getName(), row.getDeletedAt() != null))
                    .orElseThrow(() -> ApiException.notFound(type.label() + "不存在: " + id));
            case CLASS -> classes.findById(id)
                    .map(row -> new EntityRow(type, id, row.getName(), row.getDeletedAt() != null))
                    .orElseThrow(() -> ApiException.notFound(type.label() + "不存在: " + id));
            case COURSE -> courses.findById(id)
                    .map(row -> new EntityRow(type, id, row.getName(), row.getDeletedAt() != null))
                    .orElseThrow(() -> ApiException.notFound(type.label() + "不存在: " + id));
            case GRADE -> grades.findById(id)
                    .map(row -> new EntityRow(type, id, row.getName(), row.getDeletedAt() != null))
                    .orElseThrow(() -> ApiException.notFound(type.label() + "不存在: " + id));
            case SEMESTER -> semesters.findById(id)
                    .map(row -> new EntityRow(type, id, row.getName(), row.getDeletedAt() != null))
                    .orElseThrow(() -> ApiException.notFound(type.label() + "不存在: " + id));
            case ASSIGNMENT -> {
                Assignment assignment = requireAssignment(id);
                yield new EntityRow(type, id, assignmentLabel(assignment), assignment.getDeletedAt() != null);
            }
        };
    }

    private Assignment requireAssignment(Long id) {
        return assignments.findById(id)
                .orElseThrow(() -> ApiException.notFound("授课任务不存在: " + id));
    }

    private void markDeleted(EntityType type, Long id, Instant now) {
        switch (type) {
            case TEACHER -> teachers.findById(id).ifPresent(row -> {
                row.setDeletedAt(now);
                row.setActive(false);
            });
            case CLASS -> classes.findById(id).ifPresent(row -> {
                row.setDeletedAt(now);
                row.setActive(false);
            });
            case COURSE -> courses.findById(id).ifPresent(row -> {
                row.setDeletedAt(now);
                row.setActive(false);
            });
            case GRADE -> grades.findById(id).ifPresent(row -> {
                row.setDeletedAt(now);
                row.setActive(false);
            });
            case SEMESTER -> semesters.findById(id).ifPresent(row -> {
                row.setDeletedAt(now);
                row.setActive(false);
            });
            case ASSIGNMENT -> assignments.findById(id).ifPresent(row -> {
                row.setDeletedAt(now);
                row.setActive(false);
            });
        }
    }

    private boolean isActive(EntityType type, Long id) {
        return switch (type) {
            case TEACHER -> teachers.findById(id).map(Teacher::isActive).orElse(false);
            case CLASS -> classes.findById(id).map(AdminClass::isActive).orElse(false);
            case COURSE -> courses.findById(id).map(Course::isActive).orElse(false);
            case GRADE -> grades.findById(id).map(Grade::isActive).orElse(false);
            case SEMESTER -> semesters.findById(id).map(Semester::isActive).orElse(false);
            case ASSIGNMENT -> assignments.findById(id).map(Assignment::isActive).orElse(false);
        };
    }

    private void setActive(EntityType type, Long id, boolean active) {
        switch (type) {
            case TEACHER -> teachers.findById(id).ifPresent(row -> row.setActive(active));
            case CLASS -> classes.findById(id).ifPresent(row -> row.setActive(active));
            case COURSE -> courses.findById(id).ifPresent(row -> row.setActive(active));
            case GRADE -> grades.findById(id).ifPresent(row -> row.setActive(active));
            case SEMESTER -> semesters.findById(id).ifPresent(row -> row.setActive(active));
            case ASSIGNMENT -> assignments.findById(id).ifPresent(row -> row.setActive(active));
        }
    }

    /**
     * Modules outside {@link EntityType} (for example import recipes) restore through
     * their own handler. The first supporting handler wins; no handler falls back to
     * the original six-family switch.
     */
    private boolean restoreWithHandler(String entityType, Long id) {
        for (DeletionRestoreHandler handler : restoreHandlers) {
            if (handler.supports(entityType)) {
                handler.restore(id);
                return true;
            }
        }
        return false;
    }

    private void restoreOne(EntityType type, Long id, Boolean wasActive) {
        boolean active = wasActive == null || wasActive;
        switch (type) {
            case TEACHER -> teachers.findById(id).ifPresent(row -> {
                row.setDeletedAt(null);
                row.setActive(active);
            });
            case CLASS -> classes.findById(id).ifPresent(row -> {
                row.setDeletedAt(null);
                row.setActive(active);
            });
            case COURSE -> courses.findById(id).ifPresent(row -> {
                row.setDeletedAt(null);
                row.setActive(active);
            });
            case GRADE -> grades.findById(id).ifPresent(row -> {
                row.setDeletedAt(null);
                row.setActive(active);
            });
            case SEMESTER -> semesters.findById(id).ifPresent(row -> {
                row.setDeletedAt(null);
                row.setActive(active);
            });
            case ASSIGNMENT -> assignments.findById(id).ifPresent(row -> {
                row.setDeletedAt(null);
                row.setActive(active);
            });
        }
    }

    // ---------- helpers ----------

    private boolean isCurrentSemester(Long id) {
        return semesters.findById(id).map(Semester::isCurrent).orElse(false);
    }

    private String canDeleteRefusal(EntityType type, Long id) {
        if (type == EntityType.SEMESTER && isCurrentSemester(id)) {
            return "不能删除当前学期，请先切换到其他学期";
        }
        return "该" + type.label() + "已被已发布课表或调代记录引用，只能停用";
    }

    private String cascadeRefusal(EntityType type, DeleteImpactDto impact) {
        List<String> parts = new ArrayList<>();
        if (impact.cascadeClasses() > 0) {
            parts.add(impact.cascadeClasses() + " 个行政班");
        }
        if (impact.cascadeAssignments() > 0) {
            parts.add(impact.cascadeAssignments() + " 条授课任务");
        }
        String scope = String.join("、", parts);
        return "该" + type.label() + "仍有 " + scope + "，请选择“一起删除”或“改为停用”";
    }

    private String labelOf(String entityType) {
        if ("IMPORT_RECIPE".equalsIgnoreCase(entityType)) {
            return "导入配方";
        }
        try {
            return EntityType.fromStored(entityType).label();
        } catch (ApiException ex) {
            return entityType;
        }
    }

    private RecentDeletionDto toDto(RecentDeletion row, Instant now) {
        String status;
        long daysLeft = 0;
        if (row.getRestoredAt() != null) {
            status = "RESTORED";
        } else if (now.isAfter(row.getExpiresAt())) {
            status = "EXPIRED";
        } else {
            status = "PENDING";
            daysLeft = Math.max(0, (long) Math.ceil(
                    Duration.between(now, row.getExpiresAt()).toMinutes() / 1440.0));
        }
        return new RecentDeletionDto(
                row.getId(), row.getBatchId(), row.getEntityType(), row.getEntityId(),
                row.getEntityLabel(), row.isPrimaryFlag(), row.getDeletedBy(), row.getDeletedAt(),
                row.getExpiresAt(), row.getRestoredAt(), row.getRestoredBy(), status, daysLeft);
    }

    private void audit(String action, String entityType, String detail, int successRows) {
        auditLogs.save(new AuditLog(actor(), action, entityType, detail, successRows, 0));
    }

    private String actor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return "system";
        }
        String name = authentication.getName();
        return StringUtils.hasText(name) ? name : "system";
    }

    private record EntityRow(EntityType type, Long id, String label, boolean deleted) {}
}
