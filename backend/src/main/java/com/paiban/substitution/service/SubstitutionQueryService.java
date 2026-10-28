package com.paiban.substitution.service;

import com.paiban.common.web.ApiException;
import com.paiban.foundation.domain.AdminClassRepository;
import com.paiban.foundation.domain.Teacher;
import com.paiban.foundation.domain.TeacherRepository;
import com.paiban.foundation.service.SemesterService;
import com.paiban.identity.domain.AppUser;
import com.paiban.identity.service.UserService;
import com.paiban.substitution.domain.Substitution;
import com.paiban.substitution.domain.SubstitutionRepository;
import com.paiban.substitution.web.SubstitutionDtos.SubstitutionRecordDto;
import com.paiban.substitution.web.SubstitutionDtos.TeacherStatsDto;
import com.paiban.timetable.domain.TimetableEntry;
import com.paiban.timetable.domain.TimetableEntryRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read side: records, CSV export, teacher statistics and class-scope checks. */
@Service
public class SubstitutionQueryService {

    private final SubstitutionRepository substitutions;
    private final TimetableEntryRepository entries;
    private final AdminClassRepository adminClasses;
    private final TeacherRepository teachers;
    private final SemesterService semesters;
    private final UserService users;
    private final SubstitutionMapper mapper;

    public SubstitutionQueryService(
            SubstitutionRepository substitutions,
            TimetableEntryRepository entries,
            AdminClassRepository adminClasses,
            TeacherRepository teachers,
            SemesterService semesters,
            UserService users,
            SubstitutionMapper mapper) {
        this.substitutions = substitutions;
        this.entries = entries;
        this.adminClasses = adminClasses;
        this.teachers = teachers;
        this.semesters = semesters;
        this.users = users;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public List<SubstitutionRecordDto> records(
            Long semesterId, Long teacherId, Long classId, LocalDate dateFrom, LocalDate dateTo, String type) {
        Long resolved = semesterId != null ? semesterId : semesters.requireCurrent().getId();
        List<Substitution> result = type != null && !type.isBlank()
                ? substitutions.findBySemesterIdAndStatusAndType(resolved, Substitution.EFFECTIVE, type)
                : substitutions.findBySemesterIdAndStatus(resolved, Substitution.EFFECTIVE);
        if (teacherId != null) {
            result = result.stream()
                    .filter(item -> teacherId.equals(item.getSubstituteTeacherId())
                            || teacherId.equals(item.getOriginalTeacherId()))
                    .toList();
        }
        if (classId != null) {
            Map<Long, TimetableEntry> entryMap = entries.findAll().stream()
                    .collect(Collectors.toMap(TimetableEntry::getId, Function.identity()));
            result = result.stream()
                    .filter(item -> {
                        TimetableEntry entry = entryMap.get(item.getEntryId());
                        return entry != null && classId.equals(entry.getAdminClassId());
                    })
                    .toList();
        }
        if (dateFrom != null) {
            result = result.stream().filter(item -> !item.getEffectiveDate().isBefore(dateFrom)).toList();
        }
        if (dateTo != null) {
            result = result.stream().filter(item -> !item.getEffectiveDate().isAfter(dateTo)).toList();
        }
        return result.stream().map(mapper::toRecordDto).toList();
    }

    /** Semester-end export: the same filtered rows as the records page, as CSV. */
    @Transactional(readOnly = true)
    public String recordsCsv(Long semesterId, Long teacherId, Long classId,
                             LocalDate dateFrom, LocalDate dateTo, String type) {
        List<SubstitutionRecordDto> rows = records(semesterId, teacherId, classId, dateFrom, dateTo, type);
        StringBuilder csv = new StringBuilder("\uFEFF");
        csv.append("类型,生效日期,行政班,课程,原教师,代课教师,教室,原节次,目标节次,方案ID\n");
        for (SubstitutionRecordDto row : rows) {
            boolean swap = Substitution.TYPE_SWAP.equals(row.type());
            csv.append(csvCell(swap ? "调课" : "代课")).append(',')
                    .append(csvCell(row.effectiveDate() == null ? "" : row.effectiveDate().toString())).append(',')
                    .append(csvCell(row.className())).append(',')
                    .append(csvCell(row.courseName())).append(',')
                    .append(csvCell(row.originalTeacherName())).append(',')
                    .append(csvCell(swap ? "" : row.substituteTeacherName())).append(',')
                    .append(csvCell(row.roomName())).append(',')
                    .append(csvCell(row.fromPeriodLabel())).append(',')
                    .append(csvCell(row.toPeriodLabel())).append(',')
                    .append(row.planId() == null ? "" : row.planId()).append('\n');
        }
        return csv.toString();
    }

    private static String csvCell(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    @Transactional(readOnly = true)
    public TeacherStatsDto stats(Long teacherId) {
        SubstitutionLookups.requireTeacher(teachers, teacherId);
        Long semesterId = semesters.requireCurrent().getId();
        // 代入/代出口径（票 04，规格第 7 节）：按「当前有效授课安排 vs 基线教师」比较，
        // 不按累计事件行数计数。判定谓词见 {@link RevisionPolicy#countsAsCurrentCover}：
        // 只数当前生效的非恢复代课覆盖——A→B→C 后 A 被代 1、C 代课 1、B 为 0；
        // 恢复基线行净变化为零，对任何一方都不计入；被修订替代的旧行退出统计，历史保留。
        List<Substitution> effective = substitutions.findBySemesterIdAndStatus(semesterId, Substitution.EFFECTIVE);
        long out = effective.stream()
                .filter(RevisionPolicy::countsAsCurrentCover)
                .filter(row -> teacherId.equals(row.getOriginalTeacherId()))
                .count();
        long in = effective.stream()
                .filter(RevisionPolicy::countsAsCurrentCover)
                .filter(row -> teacherId.equals(row.getSubstituteTeacherId()))
                .count();
        long swaps = substitutions.countSwapPlans(semesterId, teacherId);
        long weeklyLessons = entries.findBySemesterIdAndTeacherId(semesterId, teacherId).size();
        // A cover moves one lesson from the original teacher to the substitute; a
        // swap only moves the slot, so the teacher's load is unchanged.
        long netLessons = weeklyLessons + in - out;
        Teacher teacher = teachers.findById(teacherId).orElse(null);
        return new TeacherStatsDto(teacherId, teacher == null ? null : teacher.getName(),
                out, in, swaps, weeklyLessons, netLessons);
    }

    @Transactional(readOnly = true)
    public void requireHeadTeacher(String username, Long classId) {
        AppUser user = users.getByUsername(username);
        boolean headTeacher = user.getTeacherId() != null
                && adminClasses.findById(classId)
                        .map(adminClass -> user.getTeacherId().equals(adminClass.getHeadTeacherId()))
                        .orElse(false);
        if (!headTeacher) {
            throw ApiException.forbidden("只能查看本班的调代记录");
        }
    }

    @Transactional(readOnly = true)
    public List<SubstitutionRecordDto> myRecords(String username) {
        AppUser user = users.getByUsername(username);
        if (user.getTeacherId() == null) {
            return List.of();
        }
        return records(null, user.getTeacherId(), null, null, null, null);
    }

    @Transactional(readOnly = true)
    public List<SubstitutionRecordDto> allRecords() {
        return new ArrayList<>(records(null, null, null, null, null, null));
    }
}
