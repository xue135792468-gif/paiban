package com.paiban.ai.imports;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The day/period vocabulary shared by the slot merge and the timetable importer. */
class SlotTextTest {

    @Test
    void periodNumbersAcceptTheDayPartPrefixesRealWorkbooksUse() {
        assertThat(SlotText.periodNo("上午第1节")).contains(1);
        assertThat(SlotText.periodNo("下午第5节")).contains(5);
        assertThat(SlotText.periodNo("晚上第9节")).contains(9);
        assertThat(SlotText.periodNo("中午 第 3 节")).contains(3);
        assertThat(SlotText.periodNo("上午第一节")).contains(1);
        assertThat(SlotText.periodNo("第1节")).contains(1);
        assertThat(SlotText.periodNo("1")).contains(1);
        assertThat(SlotText.periodNo("三")).contains(3);
        assertThat(SlotText.periodNo("上午")).isEmpty();
        assertThat(SlotText.periodNo("")).isEmpty();
    }

    @Test
    void weekdaysAndNamesNormalizeConsistently() {
        assertThat(SlotText.day("星期一")).contains(1);
        assertThat(SlotText.day("周一")).contains(1);
        assertThat(SlotText.day("礼拜天")).contains(7);
        assertThat(SlotText.day("7")).contains(7);
        assertThat(SlotText.day("上午")).isEmpty();
        assertThat(SlotText.normalize(" 星期一 第 1 节 ")).isEqualTo("星期一第1节");
        assertThat(SlotText.normalizeName(" 张三 ")).isEqualTo("张三");
    }
}
