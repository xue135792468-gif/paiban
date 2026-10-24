package com.paiban.ai.imports.workbook;

import static org.assertj.core.api.Assertions.assertThat;

import com.paiban.ai.imports.workbook.ImportVocabulary.Field;
import java.util.List;
import org.junit.jupiter.api.Test;

class CompositeColumnsTest {

    private static final List<String> LABELS = List.of("数据源", "课程", "教师", "教室");

    @Test
    void splitsPackedClassDayPeriodIntoSeparateFields() {
        List<List<String>> rows = List.of(
                List.of("八（1）|星期一|1", "语文", "张三", ""),
                List.of("八（1）|星期一|2", "数学", "李四", ""),
                List.of("八（2）|星期二|1", "英语", "王五", ""),
                List.of("八（2）|星期二|2", "物理", "赵六", ""));

        CompositeColumns.Split split = CompositeColumns.split(LABELS, rows);

        assertThat(split.derived()).extracting(CompositeColumns.Derived::field)
                .containsExactly(Field.CLASS_NAME, Field.DAY, Field.PERIOD);
        assertThat(split.extraCells().get(0)).containsExactly("八（1）", "星期一", "1");
        assertThat(split.extraCells().get(3)).containsExactly("八（2）", "星期二", "2");
    }

    @Test
    void normalColumnsAreLeftAlone() {
        List<List<String>> rows = List.of(
                List.of("课程表", "语文", "张三"),
                List.of("课程表", "数学", "李四"),
                List.of("课程表", "英语", "王五"));

        CompositeColumns.Split split = CompositeColumns.split(List.of("名称", "课程", "教师"), rows);

        assertThat(split.empty()).isTrue();
    }

    @Test
    void packedPairWithoutWeekdayOrPeriodIsNotSplit() {
        List<List<String>> rows = List.of(
                List.of("语文|张三"),
                List.of("数学|李四"),
                List.of("英语|王五"));

        CompositeColumns.Split split = CompositeColumns.split(List.of("课程"), rows);

        assertThat(split.empty()).isTrue();
    }

    @Test
    void separatorBelowEightyPercentDoesNotSplit() {
        List<List<String>> rows = List.of(
                List.of("八（1）|星期一|1"),
                List.of("八（1）|星期一|2"),
                List.of("八（2）星期二 1"),
                List.of("单独的班"));

        CompositeColumns.Split split = CompositeColumns.split(List.of("数据源"), rows);

        assertThat(split.empty()).isTrue();
    }
}
