package com.paiban.ai.imports.workbook;

import static org.assertj.core.api.Assertions.assertThat;

import com.paiban.ai.imports.workbook.ImportVocabulary.Field;
import java.util.List;
import org.junit.jupiter.api.Test;

class HeaderDetectionTest {

    @Test
    void findsTheHeaderRowFromFieldWords() {
        List<List<String>> rows = List.of(
                List.of("班级", "星期", "节次", "课程", "教师"),
                List.of("八（1）", "星期一", "1", "语文", "张三"));

        HeaderDetection.Header header = HeaderDetection.detect(rows);

        assertThat(header.rowIndex()).isZero();
        assertThat(header.dataStart()).isEqualTo(1);
        assertThat(header.fields()).contains(
                Field.CLASS_NAME, Field.DAY, Field.PERIOD, Field.COURSE, Field.TEACHER);
    }

    @Test
    void titleRowAboveTheHeaderIsSkipped() {
        List<List<String>> rows = List.of(
                List.of("班级课表"),
                List.of("班级", "星期", "节次"),
                List.of("八（1）", "星期一", "1"));

        HeaderDetection.Header header = HeaderDetection.detect(rows);

        assertThat(header.rowIndex()).isEqualTo(1);
        assertThat(header.labels()).containsExactly("班级", "星期", "节次");
    }

    @Test
    void headerlessSheetKeepsItsFirstDataRow() {
        List<List<String>> rows = List.of(
                List.of("八（1）", "星期一", "1", "语文", "张三"),
                List.of("八（1）", "星期一", "2", "数学", "李四"));

        HeaderDetection.Header header = HeaderDetection.detect(rows);

        assertThat(header.headerless()).isTrue();
        assertThat(header.dataStart()).isZero();
        assertThat(header.labels()).containsExactly("列1", "列2", "列3", "列4", "列5");
    }

    @Test
    void headerlessSheetWithTitleStartsAtTheFirstDataRow() {
        List<List<String>> rows = List.of(
                List.of("数据源"),
                List.of("八（1）", "星期一", "1", "语文", "张三"),
                List.of("八（1）", "星期一", "2", "数学", "李四"));

        HeaderDetection.Header header = HeaderDetection.detect(rows);

        assertThat(header.headerless()).isTrue();
        assertThat(header.dataStart()).isEqualTo(1);
    }

    @Test
    void aTwoColumnTeacherListKeepsEveryRow() {
        List<List<String>> rows = List.of(
                List.of("张三", "语文"),
                List.of("李四", "数学"),
                List.of("王五", "英语"));

        HeaderDetection.Header header = HeaderDetection.detect(rows);

        assertThat(header.headerless()).isTrue();
        assertThat(header.dataStart()).isZero();
    }

    @Test
    void instructionSheetIsSkippedByTheProfiler() {
        ProfiledSheet sheet = SheetProfiler.profile("使用说明", List.of(
                List.of("使用说明"),
                List.of("1. 本表为导出的查询结果"),
                List.of("2. 请勿修改")));

        assertThat(sheet.skipped()).isTrue();
        assertThat(sheet.skippedReason()).contains("说明");
    }

    @Test
    void profilerAppendsDerivedColumnsToTheGrid() {
        ProfiledSheet sheet = SheetProfiler.profile("数据源", List.of(
                List.of("数据源", "课程", "教师"),
                List.of("八（1）|星期一|1", "语文", "张三"),
                List.of("八（1）|星期一|2", "数学", "李四"),
                List.of("八（2）|星期二|1", "英语", "王五")));

        assertThat(sheet.labels()).contains("数据源·班级", "数据源·星期", "数据源·节次");
        assertThat(sheet.dataRows().get(0)).endsWith("八（1）", "星期一", "1");
        assertThat(sheet.grid().get(0)).isEqualTo(sheet.labels());
    }
}
