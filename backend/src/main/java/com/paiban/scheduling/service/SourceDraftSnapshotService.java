package com.paiban.scheduling.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paiban.common.web.ApiException;
import com.paiban.foundation.domain.Period;
import com.paiban.foundation.domain.PeriodRepository;
import com.paiban.foundation.domain.Teacher;
import com.paiban.foundation.domain.TeacherRepository;
import com.paiban.scheduling.domain.Assignment;
import com.paiban.scheduling.domain.AssignmentRepository;
import com.paiban.scheduling.domain.DraftLesson;
import com.paiban.scheduling.domain.DraftLessonRepository;
import com.paiban.scheduling.domain.TimetableDraft;
import com.paiban.scheduling.domain.TimetableDraftRepository;
import com.paiban.scheduling.service.SourceDraftSnapshot.SourceLesson;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Captures an explicit source draft as an immutable snapshot and checks it is still a
 * truthful description of the teaching assignments.
 *
 * <p>The snapshot is the only input the solver may use for "continue optimising this
 * draft". It is frozen at submission (stored on the job) so a concurrent edit to the
 * source draft or to an assignment cannot be mixed into the run half-way. Locks are keyed
 * by {@code (assignmentId, lessonIndex)}, never by row id, because the new draft gets new
 * ids and the old draft is left untouched (ADR-0034).
 */
@Service
public class SourceDraftSnapshotService {

    private static final String MISMATCH = "来源草稿与授课任务不一致（任务 ";

    private final TimetableDraftRepository drafts;
    private final DraftLessonRepository draftLessons;
    private final AssignmentRepository assignments;
    private final TeacherRepository teachers;
    private final PeriodRepository periods;
    private final ObjectMapper objectMapper;

    public SourceDraftSnapshotService(
            TimetableDraftRepository drafts,
            DraftLessonRepository draftLessons,
            AssignmentRepository assignments,
            TeacherRepository teachers,
            PeriodRepository periods,
            ObjectMapper objectMapper) {
        this.drafts = drafts;
        this.draftLessons = draftLessons;
        this.assignments = assignments;
        this.teachers = teachers;
        this.periods = periods;
        this.objectMapper = objectMapper;
    }

    /**
     * Validates and freezes one draft. The draft row is locked for the capture so a
     * concurrent editor cannot change rows between the version/row reads; the snapshot
     * is one point in time, never a mix of two. Every refusal happens before a job
     * exists. The checks are ordered from the draft itself, to its lesson rows, to the
     * teaching assignments, so the message names the first thing that is wrong.
     */
    @Transactional
    public SourceDraftSnapshot capture(Long draftId, Long expectedSemesterId) {
        TimetableDraft draft = drafts.findWithLockById(draftId)
                .orElseThrow(() -> ApiException.notFound("课表草稿不存在: " + draftId));
        Long semesterId = draft.getSemesterId();
        if (expectedSemesterId != null && !expectedSemesterId.equals(semesterId)) {
            throw ApiException.badRequest("来源草稿不属于当前学期");
        }
        if (!"DRAFT".equals(draft.getStatus())) {
            throw ApiException.badRequest("只能基于未发布的草稿继续优化");
        }
        List<DraftLesson> rows = draftLessons.findByDraftIdOrderByIdAsc(draftId);
        if (rows.isEmpty()) {
            throw ApiException.badRequest("来源草稿没有课节");
        }

        List<Assignment> semesterAssignments = assignments.findBySemesterId(semesterId);
        Map<Long, Assignment> assignmentById = new HashMap<>();
        semesterAssignments.forEach(assignment -> assignmentById.put(assignment.getId(), assignment));
        Map<Long, Period> periodById = new HashMap<>();
        periods.findBySemesterIdOrderByDayOfWeekAscPeriodNoAsc(semesterId)
                .forEach(period -> periodById.put(period.getId(), period));

        Map<Long, List<DraftLesson>> rowsByAssignment = new LinkedHashMap<>();
        for (DraftLesson row : rows) {
            if (!assignmentById.containsKey(row.getAssignmentId())) {
                throw ApiException.badRequest("来源草稿引用了不存在的授课任务: " + row.getAssignmentId());
            }
            rowsByAssignment
                    .computeIfAbsent(row.getAssignmentId(), key -> new ArrayList<>())
                    .add(row);
        }

        Set<Long> teacherIds = new HashSet<>();
        rows.forEach(row -> teacherIds.add(row.getTeacherId()));
        Set<Long> knownTeachers = new HashSet<>();
        teachers.findAllById(teacherIds).stream()
                .map(Teacher::getId)
                .forEach(knownTeachers::add);

        List<Assignment> ordered = new ArrayList<>(semesterAssignments);
        ordered.sort(Comparator.comparing(Assignment::getId));
        List<SourceLesson> lessons = new ArrayList<>();
        for (Assignment assignment : ordered) {
            int required = assignment.getWeeklyLessons();
            List<DraftLesson> assignmentRows =
                    rowsByAssignment.getOrDefault(assignment.getId(), List.of());
            if (assignmentRows.size() != required) {
                throw ApiException.badRequest(MISMATCH + assignment.getId() + " 需要 "
                        + required + " 节，实际 " + assignmentRows.size() + " 节）");
            }
            Map<Integer, DraftLesson> byIndex = new HashMap<>();
            for (DraftLesson row : assignmentRows) {
                if (row.getLessonIndex() < 0 || row.getLessonIndex() >= required
                        || byIndex.put(row.getLessonIndex(), row) != null) {
                    throw ApiException.badRequest(MISMATCH + assignment.getId()
                            + " 的课节序号不完整，无法可靠继承锁定）");
                }
            }
            for (int index = 0; index < required; index++) {
                DraftLesson row = byIndex.get(index);
                if (!Objects.equals(row.getAdminClassId(), assignment.getAdminClassId())) {
                    throw ApiException.badRequest(MISMATCH + assignment.getId()
                            + " 的班级与授课任务不符）");
                }
                if (!Objects.equals(row.getCourseId(), assignment.getCourseId())) {
                    throw ApiException.badRequest(MISMATCH + assignment.getId()
                            + " 的课程与授课任务不符）");
                }
                if (!knownTeachers.contains(row.getTeacherId())) {
                    throw ApiException.badRequest("来源草稿引用了不存在的教师: " + row.getTeacherId());
                }
                if (row.isLocked() && row.getPeriodId() == null) {
                    throw ApiException.badRequest("锁定课节没有时间，无法继续优化");
                }
                if (row.getPeriodId() != null && !periodById.containsKey(row.getPeriodId())) {
                    throw ApiException.badRequest(
                            "来源草稿引用了当前学期不存在的作息时段: " + row.getPeriodId());
                }
                lessons.add(new SourceLesson(
                        assignment.getId(),
                        index,
                        row.getAdminClassId(),
                        row.getCourseId(),
                        row.getTeacherId(),
                        row.getStudentCount(),
                        row.getPeriodId(),
                        row.getRoomId(),
                        row.isLocked()));
            }
        }
        if (lessons.stream().allMatch(SourceLesson::locked)) {
            throw ApiException.conflict("全部课节已锁定，没有可继续优化的课程");
        }
        return new SourceDraftSnapshot(
                draft.getId(),
                draft.getName(),
                draft.getVersion(),
                assignmentFingerprint(semesterAssignments),
                lessons);
    }

    /**
     * Recomputes the assignment fingerprint. The run calls this before solving and again
     * before persisting, so a task edited while the solver was busy fails the job instead
     * of producing a new draft based on stale identity.
     */
    @Transactional(readOnly = true)
    public void requireAssignmentsUnchanged(SourceDraftSnapshot snapshot, Long semesterId) {
        String current = assignmentFingerprint(assignments.findBySemesterId(semesterId));
        if (!current.equals(snapshot.assignmentFingerprint())) {
            throw new IllegalStateException("授课任务在优化开始后发生变化，请重新发起");
        }
    }

    public int lockedCount(SourceDraftSnapshot snapshot) {
        return (int) snapshot.lessons().stream().filter(SourceLesson::locked).count();
    }

    public String toJson(SourceDraftSnapshot snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("来源草稿快照无法序列化", ex);
        }
    }

    public SourceDraftSnapshot fromJson(String json) {
        if (!StringUtils.hasText(json)) {
            throw new IllegalArgumentException("来源草稿快照为空，请重新发起优化");
        }
        try {
            return objectMapper.readValue(json, SourceDraftSnapshot.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("来源草稿快照损坏，请重新发起优化", ex);
        }
    }

    /** SHA-256 over the semester's assignments in id order; any edit changes the value. */
    static String assignmentFingerprint(List<Assignment> semesterAssignments) {
        List<Assignment> ordered = new ArrayList<>(semesterAssignments);
        ordered.sort(Comparator.comparing(Assignment::getId));
        StringBuilder text = new StringBuilder();
        for (Assignment assignment : ordered) {
            text.append(assignment.getId()).append('|')
                    .append(assignment.getAdminClassId()).append('|')
                    .append(assignment.getCourseId()).append('|')
                    .append(assignment.getTeacherId()).append('|')
                    .append(assignment.getWeeklyLessons()).append('\n');
        }
        return sha256(text.toString());
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                hex.append(Character.forDigit((value >> 4) & 0xF, 16));
                hex.append(Character.forDigit(value & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }
}
