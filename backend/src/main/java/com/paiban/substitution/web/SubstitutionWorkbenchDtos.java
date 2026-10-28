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

    /** 候选列表 + 来源课信息 + 锁定状态（旧格式只读 / 已有待审批单）。 */
    public record CandidateListDto(Long entryId,
                                   LocalDate lessonDate,
                                   Long classId,
                                   String className,
                                   Long courseId,
                                   String courseName,
                                   String courseSubject,
                                   Long currentTeacherId,
                                   String currentTeacherName,
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

    /** 预览结果：凭证 + 权威截止时间 + 左右对照 + 完整变化清单 + 提示性校验问题。 */
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
                                  boolean canConfirm) {}

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
}
