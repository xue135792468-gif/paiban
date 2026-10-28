package com.paiban.substitution.web;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** 管理调代工作台（票 03）的 HTTP 契约：候选、预览凭证与两种消费动作。 */
public final class SubstitutionWorkbenchDtos {

    private SubstitutionWorkbenchDtos() {}

    public record PreviewRequest(@NotNull(message = "请选择课节") Long entryId,
                                 @NotNull(message = "请选择授课日期") LocalDate lessonDate,
                                 @NotNull(message = "请选择代课教师") Long substituteTeacherId,
                                 String reason) {}

    /** 提交审批与亲自确认共用；幂等键 = 凭证 id + 动作 + 该请求体的哈希。 */
    public record ConsumeRequest(String comment) {}

    /** 一位可选代课教师：启用、排除当前教师，附同科/冲突提示，服务端只提示不裁决。 */
    public record CandidateDto(Long teacherId,
                               String name,
                               String subject,
                               boolean sameSubject,
                               boolean busyAtPeriod,
                               String busyReason,
                               boolean crossSubject) {}

    /** 候选列表 + 来源课信息 + 锁定状态（旧格式只读 / 已有待审批单）+ 基线教师（恢复基线入口）。 */
    public record CandidateListDto(Long entryId,
                                   LocalDate lessonDate,
                                   Long classId,
                                   String className,
                                   Long courseId,
                                   String courseName,
                                   String courseSubject,
                                   Long currentTeacherId,
                                   String currentTeacherName,
                                   Long baselineTeacherId,
                                   String baselineTeacherName,
                                   Long periodId,
                                   int periodNo,
                                   String periodLabel,
                                   boolean legacyLocked,
                                   Long activeRequestId,
                                   List<CandidateDto> candidates) {}

    /** 对照格子：调整前/后面板共用的单格投影。 */
    public record GridCellDto(Long entryId,
                              int periodNo,
                              String periodLabel,
                              String courseName,
                              String className,
                              Long teacherId,
                              String teacherName,
                              String roomName,
                              boolean changed) {}

    /** 变化清单逐项：课程、班级、调整前后实际日期/节次、教师及教室。 */
    public record ChangeItemDto(Long entryId,
                                Long classId,
                                String className,
                                Long courseId,
                                String courseName,
                                LocalDate lessonDate,
                                String periodLabelBefore,
                                String periodLabelAfter,
                                Long teacherIdBefore,
                                String teacherNameBefore,
                                Long teacherIdAfter,
                                String teacherNameAfter,
                                String roomNameBefore,
                                String roomNameAfter,
                                String changeKind) {}

    /** 预览结果：凭证 + 权威截止时间 + 左右对照 + 完整变化清单 + 提示性校验问题。
     *
     * <p>修订预览（票 04）：revisesRequestId 指向被再次调整的已生效申请，
     * revisionKind = CHANGE_SUBSTITUTE（换代课人）或 RESTORE_BASELINE（恢复基线）；
     * 首次代课两者为空。 */
    public record PreviewResponse(Long voucherId,
                                  Instant deadlineAt,
                                  Instant expiresAt,
                                  LocalDate lessonDate,
                                  Long classId,
                                  String className,
                                  Long sourceEntryId,
                                  Long previousTeacherId,
                                  String previousTeacherName,
                                  Long substituteTeacherId,
                                  String substituteTeacherName,
                                  boolean sameSubject,
                                  List<GridCellDto> left,
                                  List<GridCellDto> right,
                                  List<ChangeItemDto> changes,
                                  List<String> issues,
                                  boolean canSubmitApproval,
                                  boolean canConfirm,
                                  Long revisesRequestId,
                                  String revisionKind) {}

    /** 两种消费动作的统一结果；幂等重放时返回第一次存储的结果并标记 replayed。 */
    public record WorkbenchActionResult(Long requestId, String status, boolean replayed) {

        public String toJson() {
            return "{\"requestId\":" + requestId + ",\"status\":\"" + status + "\",\"replayed\":false}";
        }

        /** 幂等重放：把第一次存储的结果原样还原为响应。 */
        public static WorkbenchActionResult fromJson(String json) {
            try {
                var root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
                return new WorkbenchActionResult(
                        root.path("requestId").asLong(),
                        root.path("status").asText(null),
                        true);
            } catch (java.io.IOException ex) {
                throw new IllegalStateException("预览凭证结果损坏: " + json, ex);
            }
        }
    }

    // ------------------------- 票 05：管理员同班调课（SWAP）的 HTTP 契约 -------------------------

    /** 指定目标日期里，来源班当前已有课的槽位（可作置换目标的候选格）。 */
    public record SwapTargetSlotDto(Long periodId,
                                    int periodNo,
                                    Long courseId,
                                    String courseName,
                                    Long teacherId,
                                    String teacherName) {}

    /** 目标日期的班级槽位清单；空列表 = 当天是空格（空格只挪不补）。 */
    public record SwapTargetsResponse(Long entryId,
                                      LocalDate lessonDate,
                                      LocalDate targetDate,
                                      Long classId,
                                      String className,
                                      List<SwapTargetSlotDto> slots) {}

    /** 生成置换环候选的请求：目标日期必须明确，绝不静默替换。 */
    public record SwapPlanGenerateRequest(@NotNull(message = "请选择课节") Long entryId,
                                          @NotNull(message = "请选择授课日期") LocalDate lessonDate,
                                          @NotNull(message = "请选择目标日期") LocalDate targetDate,
                                          @NotNull(message = "请选择目标课节") Long targetPeriodId,
                                          String rankingPreset,
                                          String reason) {}

    /** 一个置换环候选：整环的每一步移动（含实际日期与名称解析）。 */
    public record SwapCandidateDto(Long planId,
                                   int impactDays,
                                   int impactTeachers,
                                   int softPenalty,
                                   int cycleLength,
                                   boolean sameWeek,
                                   List<com.paiban.substitution.web.SubstitutionDtos.PlanMoveDto> moves) {}

    /** 生成方案结果：≤3 个候选 + 无解原因（无解时原课表不动、零申请）。 */
    public record SwapPlansResponse(Long entryId,
                                    LocalDate lessonDate,
                                    Long classId,
                                    String className,
                                    Long courseId,
                                    String courseName,
                                    LocalDate targetDate,
                                    Long targetPeriodId,
                                    Integer targetPeriodNo,
                                    String rankingPreset,
                                    String rankingPresetLabel,
                                    List<SwapCandidateDto> plans,
                                    List<com.paiban.substitution.web.SubstitutionDtos.DiagnosisDto> diagnoses) {}

    /** 选定候选方案创建 SWAP 预览凭证；目标从方案读回，服务端权威。 */
    public record SwapPreviewCreateRequest(@NotNull(message = "请选择课节") Long entryId,
                                           @NotNull(message = "请选择授课日期") LocalDate lessonDate,
                                           @NotNull(message = "请选择方案") Long planId,
                                           String reason) {}

    /** 单日左右对照：左 = 调整前最新，右 = 应用整环方案后。 */
    public record SwapDayCompareDto(LocalDate date,
                                    List<GridCellDto> left,
                                    List<GridCellDto> right) {}

    /** 变化清单逐项：课程、班级、前后实际日期/节次、教师与教室（SWAP 教师跟课走）。 */
    public record SwapChangeItemDto(Long entryId,
                                    Long classId,
                                    String className,
                                    Long courseId,
                                    String courseName,
                                    LocalDate fromDate,
                                    String fromLabel,
                                    LocalDate toDate,
                                    String toLabel,
                                    Long teacherId,
                                    String teacherName,
                                    String roomName,
                                    String changeKind) {}

    /** SWAP 预览结果：凭证 + 环内最小开课截止 + 逐日左右对照 + 完整变化清单。 */
    public record SwapPreviewResponse(Long voucherId,
                                      Long planId,
                                      Instant deadlineAt,
                                      Instant expiresAt,
                                      Long entryId,
                                      LocalDate lessonDate,
                                      LocalDate targetDate,
                                      Long targetPeriodId,
                                      Integer targetPeriodNo,
                                      Long classId,
                                      String className,
                                      List<Long> affectedTeacherIds,
                                      List<String> affectedTeacherNames,
                                      List<SwapDayCompareDto> days,
                                      List<SwapChangeItemDto> changes,
                                      List<String> issues,
                                      boolean canSubmitApproval,
                                      boolean canConfirm) {}

    /** 切换视角（班级/教师）后的单日左右对照；日期与视角两侧联动。 */
    public record SwapCompareResponse(LocalDate date,
                                      String view,
                                      Long viewId,
                                      String viewName,
                                      List<GridCellDto> left,
                                      List<GridCellDto> right) {}
}
