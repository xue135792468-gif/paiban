package com.paiban.substitution.plan;

import java.time.LocalDate;

/**
 * 一个已冻结的槽位（票 07，规格第 6 节）：当前安排的开课时刻已过的授课时间。
 * 生成置换环候选时求解器绝不把它选作环节点——已开始或已结束的授课任何路径
 * 都不可改变。判定逻辑见 {@code com.paiban.substitution.service.LessonFreeze}。
 */
public record FrozenSlot(LocalDate date, long periodId) {}
