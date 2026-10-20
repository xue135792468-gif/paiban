package com.paiban.foundation.service;

import com.paiban.common.web.ApiException;
import com.paiban.foundation.domain.Period;
import com.paiban.foundation.domain.PeriodRepository;
import com.paiban.foundation.domain.PeriodSection;
import com.paiban.foundation.domain.Semester;
import com.paiban.foundation.domain.SemesterRepository;
import com.paiban.foundation.web.SemesterDtos.GenerateDefaultPeriodsRequest;
import com.paiban.foundation.web.SemesterDtos.PeriodDto;
import com.paiban.foundation.web.SemesterDtos.PeriodSlot;
import com.paiban.foundation.web.SemesterDtos.SemesterDto;
import com.paiban.foundation.web.SemesterDtos.SaveSemesterRequest;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SemesterService {

    private final SemesterRepository semesters;
    private final PeriodRepository periods;

    public SemesterService(SemesterRepository semesters, PeriodRepository periods) {
        this.semesters = semesters;
        this.periods = periods;
    }

    @Transactional(readOnly = true)
    public List<SemesterDto> list() {
        return semesters.findAllByOrderByStartDateDesc().stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public SemesterDto get(Long id) {
        return toDto(require(id));
    }

    @Transactional
    public SemesterDto create(SaveSemesterRequest request) {
        validateDates(request);
        if (semesters.existsByName(request.name())) {
            throw ApiException.conflict("学期名称已存在: " + request.name());
        }
        Semester semester = new Semester();
        semester.setName(request.name());
        semester.setStartDate(request.startDate());
        semester.setEndDate(request.endDate());
        semester.setCreatedAt(Instant.now());
        return toDto(semesters.save(semester));
    }

    @Transactional
    public SemesterDto update(Long id, SaveSemesterRequest request) {
        validateDates(request);
        Semester semester = require(id);
        if (!semester.getName().equals(request.name()) && semesters.existsByName(request.name())) {
            throw ApiException.conflict("学期名称已存在: " + request.name());
        }
        semester.setName(request.name());
        semester.setStartDate(request.startDate());
        semester.setEndDate(request.endDate());
        return toDto(semester);
    }

    @Transactional
    public void delete(Long id) {
        Semester semester = require(id);
        if (semester.isCurrent()) {
            throw ApiException.conflict("不能删除当前学期，请先切换到其他学期");
        }
        periods.deleteBySemesterId(id);
        semesters.delete(semester);
    }

    @Transactional
    public SemesterDto setCurrent(Long id) {
        Semester target = require(id);
        semesters.findAll().forEach(semester -> semester.setCurrent(semester.getId().equals(target.getId())));
        return toDto(target);
    }

    @Transactional(readOnly = true)
    public Optional<SemesterDto> current() {
        return semesters.findByCurrentTrue().map(this::toDto);
    }

    @Transactional(readOnly = true)
    public Semester requireCurrent() {
        return semesters.findByCurrentTrue()
                .orElseThrow(() -> ApiException.badRequest("尚未设置当前学期，请先在“学期与作息”中设置"));
    }

    @Transactional(readOnly = true)
    public Semester require(Long id) {
        return semesters.findById(id).orElseThrow(() -> ApiException.notFound("学期不存在: " + id));
    }

    @Transactional(readOnly = true)
    public List<PeriodDto> listPeriods(Long semesterId) {
        require(semesterId);
        return periods.findBySemesterIdOrderByDayOfWeekAscPeriodNoAsc(semesterId).stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional
    public List<PeriodDto> replacePeriods(Long semesterId, List<PeriodSlot> slots) {
        require(semesterId);
        validateSlots(slots);
        periods.deleteBySemesterId(semesterId);
        periods.flush();
        List<Period> saved = new ArrayList<>();
        for (PeriodSlot slot : slots) {
            for (Integer day : slot.days()) {
                Period period = new Period();
                period.setSemesterId(semesterId);
                period.setDayOfWeek(day);
                period.setPeriodNo(slot.periodNo());
                period.setLabel(slot.label());
                period.setSection(slot.section());
                period.setStartTime(slot.startTime());
                period.setEndTime(slot.endTime());
                saved.add(period);
            }
        }
        return periods.saveAll(saved).stream()
                .sorted((a, b) -> a.getDayOfWeek() != b.getDayOfWeek()
                        ? Integer.compare(a.getDayOfWeek(), b.getDayOfWeek())
                        : Integer.compare(a.getPeriodNo(), b.getPeriodNo()))
                .map(this::toDto)
                .toList();
    }

    @Transactional
    public List<PeriodDto> generateDefaultPeriods(Long semesterId, GenerateDefaultPeriodsRequest request) {
        require(semesterId);
        int morning = request == null || request.morningCount() == null ? 4 : request.morningCount();
        int afternoon = request == null || request.afternoonCount() == null ? 4 : request.afternoonCount();
        int evening = request == null || request.eveningCount() == null ? 2 : request.eveningCount();
        List<Integer> days = request == null || request.days() == null || request.days().isEmpty()
                ? List.of(1, 2, 3, 4, 5)
                : request.days();
        if (morning < 0 || afternoon < 0 || evening < 0) {
            throw ApiException.badRequest("节次数不能为负数");
        }
        if (morning + afternoon + evening == 0) {
            throw ApiException.badRequest("至少需要一节课");
        }

        List<PeriodSlot> slots = new ArrayList<>();
        int periodNo = 1;
        LocalTime cursor = LocalTime.of(8, 0);
        for (int i = 0; i < morning; i++) {
            slots.add(slot(periodNo++, "上午第" + (i + 1) + "节", PeriodSection.MORNING, cursor, 45, days));
            cursor = cursor.plusMinutes(55);
        }
        cursor = LocalTime.of(14, 0);
        for (int i = 0; i < afternoon; i++) {
            slots.add(slot(periodNo++, "下午第" + (i + 1) + "节", PeriodSection.AFTERNOON, cursor, 45, days));
            cursor = cursor.plusMinutes(55);
        }
        cursor = LocalTime.of(19, 0);
        for (int i = 0; i < evening; i++) {
            slots.add(slot(periodNo++, "晚自习第" + (i + 1) + "节", PeriodSection.EVENING, cursor, 45, days));
            cursor = cursor.plusMinutes(55);
        }
        return replacePeriods(semesterId, slots);
    }

    private PeriodSlot slot(
            int periodNo, String label, PeriodSection section, LocalTime start, int duration, List<Integer> days) {
        return new PeriodSlot(periodNo, label, section, start, start.plusMinutes(duration), days);
    }

    private void validateSlots(List<PeriodSlot> slots) {
        Set<String> seen = new HashSet<>();
        for (PeriodSlot slot : slots) {
            if (slot.periodNo() == null || slot.periodNo() <= 0) {
                throw ApiException.badRequest("节次序号必须为正整数");
            }
            if (!slot.endTime().isAfter(slot.startTime())) {
                throw ApiException.badRequest("第 " + slot.periodNo() + " 节的结束时间必须晚于开始时间");
            }
            for (Integer day : slot.days()) {
                if (day == null || day < 1 || day > 7) {
                    throw ApiException.badRequest("星期取值必须在 1-7 之间");
                }
                String key = day + "-" + slot.periodNo();
                if (!seen.add(key)) {
                    throw ApiException.badRequest("星期 " + day + " 的第 " + slot.periodNo() + " 节重复配置");
                }
            }
        }
    }

    private void validateDates(SaveSemesterRequest request) {
        if (!request.endDate().isAfter(request.startDate())) {
            throw ApiException.badRequest("结束日期必须晚于开始日期");
        }
    }

    private SemesterDto toDto(Semester semester) {
        return new SemesterDto(
                semester.getId(),
                semester.getName(),
                semester.getStartDate(),
                semester.getEndDate(),
                semester.isCurrent());
    }

    private PeriodDto toDto(Period period) {
        return new PeriodDto(
                period.getId(),
                period.getDayOfWeek(),
                period.getPeriodNo(),
                period.getLabel(),
                period.getSection(),
                period.getStartTime(),
                period.getEndTime());
    }
}
