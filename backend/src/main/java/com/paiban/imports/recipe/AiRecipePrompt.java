package com.paiban.imports.recipe;

import org.springframework.stereotype.Component;

/**
 * The two messages of ticket 07's "analyse a new sheet" call.
 *
 * <p>The system message fixes the answer contract: only the five timetable fields, no
 * invented columns, one bare JSON object. The user message carries a bounded slice of
 * the sheet (header row, column labels, the first ten data rows) plus the
 * deterministic first guess, so the model never receives the whole workbook.
 */
@Component
public class AiRecipePrompt {

    public String system() {
        return """
                你是课表导入的列映射助手。用户上传了一张学校课表，你要判断每一列对应系统的哪个字段，只输出 JSON。

                系统字段：
                - className：行政班名称（例如 八（1））
                - dayOfWeek：星期（周一…周日）
                - periodNo：当天第几节
                - courseName：课程名称
                - teacherName：教师姓名

                两种布局二选一：
                1. 复合列：班级/星期/节次挤在同一个单元格里（例如「八（1）|星期一|1」），
                   用 compositeColumn 指向该列，delimiter 填实际分隔符，classColumn/dayColumn/periodColumn 留 null。
                   复合列固定按「班级、星期、节次」的顺序拆分。
                2. 分列：班级/星期/节次各占一列，用 classColumn/dayColumn/periodColumn 指向三列，
                   compositeColumn 和 delimiter 留 null。

                输出格式（只输出这一个 JSON 对象，不要 Markdown、不要解释）：
                {"layout":"LONG","sheetName":"工作表名","headerRow":0,"delimiter":"|","compositeColumn":0,
                 "classColumn":null,"dayColumn":null,"periodColumn":null,"courseColumn":1,"teacherColumn":2,
                 "ignoreColumns":[3],"reasoning":"一句话说明为什么这样映射"}

                规则：
                1. 列索引从 0 开始；不存在的列用 null，不要编造。
                2. headerRow 是表头所在行（0 基）；没有表头时填 -1。
                3. layout 在复合列布局时填 LONG，分列布局时填 GRID。
                4. 课程为空的格子表示「这个时段不上课」，不是错误，仍指向课程列即可。
                5. 无法确定的列填 null；ignoreColumns 列出与课表无关的列（教室、备注等）。
                6. 不要输出 schema 之外的字段名，不要猜测文件里没有的数据。
                """;
    }

    public String user(String contextJson) {
        return """
                这是工作表的结构摘要（表头、列名、前若干行数据，以及系统按表头猜出的映射）。
                请给出列映射建议，只返回 JSON：

                """ + contextJson;
    }
}
