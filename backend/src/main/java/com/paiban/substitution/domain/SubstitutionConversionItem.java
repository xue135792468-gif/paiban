package com.paiban.substitution.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 转换批次里的逐条留痕：该生效行转换前后的实例日期与日期依据。
 * 原始行本身不改写（effective_date、请求、历史日志全部保留），这里
 * 记录的是「把旧行解释成哪一天的实例」这件事本身的审计凭据。
 */
@Entity
@Table(name = "substitution_conversion_item")
@Getter
@Setter
@NoArgsConstructor
public class SubstitutionConversionItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "conversion_id", nullable = false)
    private Long conversionId;

    @Column(name = "substitution_id", nullable = false)
    private Long substitutionId;

    @Column(name = "entry_id", nullable = false)
    private Long entryId;

    @Column(nullable = false, length = 20)
    private String type;

    @Column(name = "lesson_date_before")
    private LocalDate lessonDateBefore;

    @Column(name = "target_lesson_date_before")
    private LocalDate targetLessonDateBefore;

    @Column(name = "lesson_date_after", nullable = false)
    private LocalDate lessonDateAfter;

    @Column(name = "target_lesson_date_after")
    private LocalDate targetLessonDateAfter;

    @Column(length = 500)
    private String basis;
}
