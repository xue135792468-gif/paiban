package com.paiban.substitution.domain;

import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 管理调代工作台的预览凭证存储。凭证是短期试算结果，不是待审批申请。 */
public interface SubstitutionPreviewRepository extends JpaRepository<SubstitutionPreview, Long> {

    Optional<SubstitutionPreview> findByIdAndStatus(Long id, String status);

    /** 同一实例同时最多一笔活跃修改的辅助查询：找同实例未消费的凭证。 */
    java.util.List<SubstitutionPreview> findByEntryIdAndLessonDateAndStatus(
            Long entryId, LocalDate lessonDate, String status);
}
