package com.paiban.substitution.cli;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.paiban.foundation.domain.PeriodSection;
import com.paiban.scheduling.constraint.ConstraintContext.ClassView;
import com.paiban.scheduling.constraint.ConstraintContext.CourseView;
import com.paiban.scheduling.constraint.ConstraintContext.PeriodView;
import com.paiban.scheduling.constraint.ConstraintContext.RoomView;
import com.paiban.scheduling.constraint.ConstraintContext.TeacherView;
import com.paiban.scheduling.domain.ConstraintConfig;
import com.paiban.scheduling.solver.ConstraintSettings;
import com.paiban.substitution.plan.CandidatePlan;
import com.paiban.substitution.plan.PlanDiagnosis;
import com.paiban.substitution.plan.PlanMove;
import com.paiban.substitution.plan.PlanSnapshot;
import com.paiban.substitution.plan.PlanVerifier;
import com.paiban.substitution.plan.PlanningResult;
import com.paiban.substitution.plan.RankingPreset;
import com.paiban.substitution.plan.SwapPlanner;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only offline command for what-if planning and after-the-fact checks.
 *
 * <p>It never opens a database and never writes business data. Everything is the
 * same pure code the web app uses, so a plan produced here is byte-for-byte the
 * same computation a server would perform.
 *
 * <pre>
 *   plan   --snapshot s.json --request r.json [--window 7] [--max-teachers 6]
 *          [--preset min-impact] [--format json|table]
 *   verify --before a.json --after b.json [--format json|table]
 *   verify --snapshot s.json --plan p.json [--kind SWAP|COVER] [--format json|table]
 * </pre>
 */
public final class SubstitutionCli {

    public static final int SCHEMA_VERSION = 1;
    public static final int EXIT_OK = 0;
    public static final int EXIT_ERROR = 1;
    public static final int EXIT_NO_SOLUTION = 2;

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    private SubstitutionCli() {}

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    public static int run(String[] args, PrintStream out, PrintStream err) {
        if (args == null || args.length == 0) {
            usage(err);
            return EXIT_ERROR;
        }
        try {
            return switch (args[0]) {
                case "plan" -> plan(args, out, err);
                case "verify" -> verify(args, out, err);
                case "help", "--help", "-h" -> {
                    usage(out);
                    yield EXIT_OK;
                }
                default -> {
                    err.println("未知命令: " + args[0]);
                    usage(err);
                    yield EXIT_ERROR;
                }
            };
        } catch (IllegalArgumentException ex) {
            err.println(ex.getMessage());
            return EXIT_ERROR;
        } catch (Exception ex) {
            err.println("命令执行失败: " + ex.getMessage());
            return EXIT_ERROR;
        }
    }

    // ------------------------------------------------------------------- plan

    private static int plan(String[] args, PrintStream out, PrintStream err) throws Exception {
        Map<String, String> options = parse(args);
        Path snapshotPath = requirePath(options, "--snapshot");
        Path requestPath = requirePath(options, "--request");
        String format = options.getOrDefault("--format", "json");
        int window = intOption(options, "--window", SwapPlanner.Options.DEFAULT_WINDOW_DAYS);
        int maxTeachers = intOption(options, "--max-teachers", SwapPlanner.Options.DEFAULT_MAX_TEACHERS);
        RankingPreset preset = RankingPreset.fromCode(options.getOrDefault("--preset", "min-impact"));

        PlanSnapshot snapshot = readSnapshot(snapshotPath);
        RequestJson request = MAPPER.readValue(requestPath.toFile(), RequestJson.class);
        if (request.schemaVersion != null && request.schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException("不支持的 schemaVersion: " + request.schemaVersion);
        }
        if (request.classId == null || request.entryId == null || request.date == null
                || request.targetPeriodId == null) {
            throw new IllegalArgumentException("请求缺少必填字段: classId / entryId / date / targetPeriodId");
        }

        PlanningResult result = SwapPlanner.plan(
                snapshot,
                new SwapPlanner.Request(request.classId, request.entryId, request.date, request.targetPeriodId),
                new SwapPlanner.Options(window, maxTeachers, preset, 50_000, 200));

        if ("table".equalsIgnoreCase(format)) {
            printPlanTable(result, out);
        } else {
            out.println(MAPPER.writeValueAsString(new PlanOutput(
                    SCHEMA_VERSION, "plan",
                    result.candidates().isEmpty() ? "NO_SOLUTION" : "OK",
                    preset.code(),
                    result.candidates().stream().map(SubstitutionCli::candidateOut).toList(),
                    result.diagnoses().stream().map(SubstitutionCli::diagnosisOut).toList(),
                    result.nodesVisited())));
        }
        return result.candidates().isEmpty() ? EXIT_NO_SOLUTION : EXIT_OK;
    }

    // ----------------------------------------------------------------- verify

    private static int verify(String[] args, PrintStream out, PrintStream err) throws Exception {
        Map<String, String> options = parse(args);
        String format = options.getOrDefault("--format", "json");
        Path snapshotPath = options.containsKey("--snapshot") ? Path.of(options.get("--snapshot")) : null;
        Path beforePath = options.containsKey("--before") ? Path.of(options.get("--before")) : null;
        Path afterPath = options.containsKey("--after") ? Path.of(options.get("--after")) : null;
        Path planPath = options.containsKey("--plan") ? Path.of(options.get("--plan")) : null;

        PlanVerifier.Result result;
        String kind;
        if (snapshotPath != null && planPath != null) {
            PlanSnapshot snapshot = readSnapshot(snapshotPath);
            PlanFile plan = MAPPER.readValue(planPath.toFile(), PlanFile.class);
            if (plan.schemaVersion != null && plan.schemaVersion != SCHEMA_VERSION) {
                throw new IllegalArgumentException("不支持的 schemaVersion: " + plan.schemaVersion);
            }
            PlanVerifier.Kind requested = options.containsKey("--kind")
                    ? PlanVerifier.Kind.valueOf(options.get("--kind").toUpperCase())
                    : PlanVerifier.Kind.SWAP;
            result = PlanVerifier.verify(
                    snapshot, plan.moves == null ? List.of() : plan.moves, requested,
                    plan.softPenalty == null ? -1 : plan.softPenalty);
            kind = requested.name();
        } else if (beforePath != null && afterPath != null) {
            result = PlanVerifier.verifyDiff(readSnapshot(beforePath), readSnapshot(afterPath));
            kind = "AUTO";
        } else {
            throw new IllegalArgumentException(
                    "verify 需要 --before/--after，或 --snapshot/--plan");
        }

        if ("table".equalsIgnoreCase(format)) {
            out.println(result.passed() ? "PASS" : "FAIL");
            result.issues().forEach(issue -> out.println(
                    "- " + issue.code() + " " + (issue.message() == null ? "" : issue.message())));
        } else {
            out.println(MAPPER.writeValueAsString(new VerifyOutput(
                    SCHEMA_VERSION, "verify", result.passed() ? "PASS" : "FAIL", kind,
                    result.softPenalty(),
                    result.issues().stream().map(issue -> new IssueOut(
                            issue.code(), issue.message(), issue.entryId(), issue.periodId())).toList())));
        }
        return result.passed() ? EXIT_OK : EXIT_ERROR;
    }

    // ---------------------------------------------------------------- mapping

    private static PlanSnapshot readSnapshot(Path path) throws Exception {
        SnapshotJson json = MAPPER.readValue(path.toFile(), SnapshotJson.class);
        if (json.schemaVersion == null || json.schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException("快照 schemaVersion 必须是 " + SCHEMA_VERSION);
        }
        if (json.semesterStart == null || json.semesterEnd == null) {
            throw new IllegalArgumentException("快照缺少 semesterStart / semesterEnd");
        }
        Map<Long, PeriodView> periods = new LinkedHashMap<>();
        for (PeriodJson period : safe(json.periods)) {
            periods.put(period.id, new PeriodView(period.id, period.dayOfWeek, period.periodNo,
                    period.section == null ? PeriodSection.MORNING : PeriodSection.valueOf(period.section)));
        }
        Map<Long, RoomView> rooms = new LinkedHashMap<>();
        for (RoomJson room : safe(json.rooms)) {
            rooms.put(room.id, new RoomView(room.id, room.name, room.type, room.capacity == null ? 0 : room.capacity));
        }
        Map<Long, CourseView> courses = new LinkedHashMap<>();
        for (CourseJson course : safe(json.courses)) {
            courses.put(course.id, new CourseView(course.id, course.name, course.requiredRoomType));
        }
        Map<Long, TeacherView> teachers = new LinkedHashMap<>();
        for (TeacherJson teacher : safe(json.teachers)) {
            teachers.put(teacher.id, new TeacherView(teacher.id, teacher.name, teacher.subject));
        }
        Map<Long, ClassView> classes = new LinkedHashMap<>();
        for (ClassJson adminClass : safe(json.classes)) {
            classes.put(adminClass.id, new ClassView(adminClass.id, adminClass.name,
                    adminClass.studentCount == null ? 0 : adminClass.studentCount, adminClass.headTeacherId));
        }
        List<PlanSnapshot.BaselineLesson> lessons = new ArrayList<>();
        for (LessonJson lesson : safe(json.lessons)) {
            if (lesson.entryId == null || lesson.classId == null || lesson.courseId == null
                    || lesson.teacherId == null || lesson.periodId == null) {
                throw new IllegalArgumentException("课节缺少必填字段: " + lesson);
            }
            lessons.add(new PlanSnapshot.BaselineLesson(
                    lesson.entryId, lesson.classId, lesson.courseId, lesson.teacherId, lesson.roomId,
                    lesson.periodId, lesson.dayOfWeek, lesson.periodNo));
        }
        List<PlanSnapshot.AppliedSubstitution> substitutions = new ArrayList<>();
        for (SubstitutionJson item : safe(json.substitutions)) {
            substitutions.add(new PlanSnapshot.AppliedSubstitution(
                    item.id == null ? 0 : item.id, item.entryId, item.effectiveDate,
                    item.createdAt == null ? Instant.EPOCH : item.createdAt,
                    item.newPeriodId, item.newDayOfWeek, item.newPeriodNo, item.newRoomId,
                    item.substituteTeacherId, item.type == null ? "COVER" : item.type));
        }
        ConstraintSettings settings = ConstraintSettings.defaults();
        if (json.constraints != null && !json.constraints.isEmpty()) {
            List<ConstraintConfig> configs = new ArrayList<>();
            for (ConstraintJson constraint : json.constraints) {
                ConstraintConfig config = new ConstraintConfig();
                config.setType(constraint.type);
                config.setEnabled(constraint.enabled == null || constraint.enabled);
                config.setHard(constraint.hard != null && constraint.hard);
                config.setWeight(constraint.weight == null ? 50 : constraint.weight);
                config.setParamsJson(MAPPER.writeValueAsString(
                        constraint.params == null ? Map.of() : constraint.params));
                configs.add(config);
            }
            settings = ConstraintSettings.from(configs, MAPPER);
        }
        return new PlanSnapshot(
                json.semesterId == null ? 0 : json.semesterId, json.semesterStart, json.semesterEnd,
                periods, rooms, courses, teachers, classes, lessons, substitutions, settings);
    }

    private static <T> List<T> safe(List<T> value) {
        return value == null ? List.of() : value;
    }

    private static CandidateOut candidateOut(CandidatePlan plan) {
        return new CandidateOut(
                plan.cycleLength(), plan.impactDays(), plan.impactTeachers(), plan.softPenalty(),
                plan.sameWeek(),
                plan.moves().stream().map(SubstitutionCli::moveOut).toList());
    }

    private static MoveOut moveOut(PlanMove move) {
        return new MoveOut(
                move.entryId(), move.classId(), move.courseId(), move.teacherId(), move.roomId(),
                move.fromPeriodId(), move.fromDayOfWeek(), move.fromPeriodNo(), move.fromDate().toString(),
                move.toPeriodId(), move.toDayOfWeek(), move.toPeriodNo(), move.toDate().toString());
    }

    private static DiagnosisOut diagnosisOut(PlanDiagnosis diagnosis) {
        return new DiagnosisOut(diagnosis.code(), diagnosis.message(),
                diagnosis.entryId(), diagnosis.periodId());
    }

    private static void printPlanTable(PlanningResult result, PrintStream out) {
        if (result.candidates().isEmpty()) {
            out.println("NO_SOLUTION");
            result.diagnoses().forEach(diagnosis ->
                    out.println("- " + diagnosis.code() + " " + diagnosis.message()));
            return;
        }
        int index = 1;
        for (CandidatePlan candidate : result.candidates()) {
            out.println("方案 " + index++ + ": 环长 " + candidate.cycleLength()
                    + "，影响 " + candidate.impactDays() + " 天，"
                    + candidate.impactTeachers() + " 位教师，软分 "
                    + candidate.softPenalty());
            candidate.moves().forEach(move -> out.println(
                    "  - 教师 " + move.teacherId() + " 课程 " + move.courseId() + " "
                            + dayLabel(move.fromDayOfWeek()) + "第" + move.fromPeriodNo() + "节"
                            + " → " + dayLabel(move.toDayOfWeek()) + "第" + move.toPeriodNo() + "节"
                            + "（" + move.toDate() + "）"));
        }
    }

    private static String dayLabel(int dayOfWeek) {
        String[] days = {"", "周一", "周二", "周三", "周四", "周五", "周六", "周日"};
        return dayOfWeek >= 1 && dayOfWeek <= 7 ? days[dayOfWeek] : "周" + dayOfWeek;
    }

    // ------------------------------------------------------------------- args

    private static Map<String, String> parse(String[] args) {
        Map<String, String> options = new LinkedHashMap<>();
        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--")) {
                throw new IllegalArgumentException("无法识别的参数: " + arg);
            }
            if (i + 1 >= args.length || args[i + 1].startsWith("--")) {
                throw new IllegalArgumentException("参数缺少值: " + arg);
            }
            options.put(arg, args[++i]);
        }
        return options;
    }

    private static Path requirePath(Map<String, String> options, String name) {
        if (!options.containsKey(name)) {
            throw new IllegalArgumentException("缺少参数 " + name);
        }
        return Path.of(options.get(name));
    }

    private static int intOption(Map<String, String> options, String name, int fallback) {
        if (!options.containsKey(name)) {
            return fallback;
        }
        try {
            return Integer.parseInt(options.get(name));
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(name + " 必须是整数");
        }
    }

    private static void usage(PrintStream stream) {
        stream.println("用法:");
        stream.println("  plan   --snapshot <快照.json> --request <请求.json> [--window 7] "
                + "[--max-teachers 6] [--preset min-impact|min-teachers|stable-timetable] "
                + "[--format json|table]");
        stream.println("  verify --before <改动前.json> --after <改动后.json> [--format json|table]");
        stream.println("  verify --snapshot <快照.json> --plan <方案.json> [--kind SWAP|COVER] "
                + "[--format json|table]");
        stream.println("退出码: 0 有解/通过, 2 无解, 1 错误/违规");
    }

    // ------------------------------------------------------------------ json

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SnapshotJson {
        public Integer schemaVersion;
        public Long semesterId;
        public LocalDate semesterStart;
        public LocalDate semesterEnd;
        public List<PeriodJson> periods;
        public List<RoomJson> rooms;
        public List<CourseJson> courses;
        public List<TeacherJson> teachers;
        public List<ClassJson> classes;
        public List<LessonJson> lessons;
        public List<SubstitutionJson> substitutions;
        public List<ConstraintJson> constraints;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PeriodJson {
        public Long id;
        public int dayOfWeek;
        public int periodNo;
        public String section;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RoomJson {
        public Long id;
        public String name;
        public String type;
        public Integer capacity;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CourseJson {
        public Long id;
        public String name;
        public String requiredRoomType;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TeacherJson {
        public Long id;
        public String name;
        public String subject;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ClassJson {
        public Long id;
        public String name;
        public Integer studentCount;
        public Long headTeacherId;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LessonJson {
        public Long entryId;
        public Long classId;
        public Long courseId;
        public Long teacherId;
        public Long roomId;
        public Long periodId;
        public int dayOfWeek;
        public int periodNo;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SubstitutionJson {
        public Long id;
        public Long entryId;
        public LocalDate effectiveDate;
        public Instant createdAt;
        public Long newPeriodId;
        public Integer newDayOfWeek;
        public Integer newPeriodNo;
        public Long newRoomId;
        public Long substituteTeacherId;
        public String type;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ConstraintJson {
        public String type;
        public Boolean enabled;
        public Boolean hard;
        public Integer weight;
        public Map<String, Object> params;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RequestJson {
        public Integer schemaVersion;
        public Long classId;
        public Long entryId;
        public LocalDate date;
        public Long targetPeriodId;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PlanFile {
        public Integer schemaVersion;
        public List<PlanMove> moves;
        public Integer softPenalty;
    }

    public record PlanOutput(int schemaVersion, String command, String status, String rankingPreset,
                             List<CandidateOut> candidates, List<DiagnosisOut> diagnoses,
                             int nodesVisited) {}

    public record CandidateOut(int cycleLength, int impactDays, int impactTeachers, int softPenalty,
                               boolean sameWeek, List<MoveOut> moves) {}

    public record MoveOut(long entryId, long classId, long courseId, long teacherId, Long roomId,
                          long fromPeriodId, int fromDayOfWeek, int fromPeriodNo, String fromDate,
                          long toPeriodId, int toDayOfWeek, int toPeriodNo, String toDate) {}

    public record DiagnosisOut(String code, String message, Long entryId, Long periodId) {}

    public record VerifyOutput(int schemaVersion, String command, String status, String kind,
                               int softPenalty, List<IssueOut> issues) {}

    public record IssueOut(String code, String message, Long entryId, Long periodId) {}
}
