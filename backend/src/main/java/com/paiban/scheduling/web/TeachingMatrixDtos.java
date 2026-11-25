package com.paiban.scheduling.web;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.paiban.common.web.StrictIntegerDeserializer;
import com.paiban.foundation.web.ImportDtos.ImportError;
import java.util.List;
import java.util.Map;

public final class TeachingMatrixDtos {

    private TeachingMatrixDtos() {}

    public record Options(
            @JsonDeserialize(contentUsing = StrictIntegerDeserializer.class) Map<String, Integer> weeklyHours,
            boolean saveAsDefaults) {

        public Options {
            if (weeklyHours == null) {
                weeklyHours = Map.of();
            }
        }
    }

    public enum Action { CREATE, UPDATE_TEACHER, UPDATE_HOURS, UPDATE, UNCHANGED }

    public record Counts(int created, int updated, int unchanged) {}

    public record Summary(Counts classes, Counts teachers, Counts courses, Counts assignments) {}

    public record TeacherPreview(String name, Action action) {}

    public record CoursePreview(String name, Action action, Integer defaultHours, Integer effectiveHours,
                                boolean missingHours) {}

    public record CellPreview(String course, String teacher, Integer weeklyLessons, Action action) {}

    public record RowPreview(int row, String className, int studentCount, String headTeacher, Action action,
                             List<CellPreview> assignments) {}

    public record CapacityWarning(int row, String className, int requested, int available) {}

    public record Report(Summary summary, List<TeacherPreview> teachers, List<CoursePreview> courses,
                         List<RowPreview> rows, List<ImportError> errors, List<CapacityWarning> warnings) {

        public boolean canCommit() {
            return errors.isEmpty() && courses.stream().noneMatch(CoursePreview::missingHours);
        }
    }
}
