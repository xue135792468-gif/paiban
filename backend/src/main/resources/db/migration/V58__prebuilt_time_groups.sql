-- 票 06：每个学期预建可编辑的「上午」「下午」时间组。
--
-- 之前「上午/下午」是前端按 start_time 折算出来的快捷项，用户改不了。改存成
-- 真正的 rule_group 行后，规则可以引用组 id（快照展开成具体坐标），用户也能
-- 增减成员、改名。
--
-- auto_filled 只对预建组为 TRUE：作息保存时服务层按新的 start_time 重算它的
-- 成员；用户一旦编辑过（PUT 分组），服务层把它置 FALSE，之后作息变化只移除
-- 已不存在的课节坐标，不再覆盖用户的选择。
--
-- 存量学期在这里补齐组和成员；新学期由 SemesterCreatedEvent 建空组，
-- PeriodsChangedEvent 再填充（SemesterService / RuleGroupService）。
-- 幂等：同名活组已存在时不重复插入，已存在的成员坐标不重复插入。

ALTER TABLE rule_group ADD COLUMN auto_filled BOOLEAN NOT NULL DEFAULT FALSE;

INSERT INTO rule_group
    (group_type, name, semester_id, live_key, auto_filled, created_at, updated_at, deleted_at)
SELECT 'TIME', '上午', s.id, CONCAT('TIME:', s.id, ':上午'), TRUE,
       CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, NULL
FROM semester s
WHERE NOT EXISTS (
    SELECT 1 FROM rule_group g
    WHERE g.semester_id = s.id AND g.group_type = 'TIME'
      AND g.name = '上午' AND g.deleted_at IS NULL
);

INSERT INTO rule_group
    (group_type, name, semester_id, live_key, auto_filled, created_at, updated_at, deleted_at)
SELECT 'TIME', '下午', s.id, CONCAT('TIME:', s.id, ':下午'), TRUE,
       CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, NULL
FROM semester s
WHERE NOT EXISTS (
    SELECT 1 FROM rule_group g
    WHERE g.semester_id = s.id AND g.group_type = 'TIME'
      AND g.name = '下午' AND g.deleted_at IS NULL
);

INSERT INTO rule_group_member
    (group_id, member_key, teacher_id, course_id, day_of_week, period_no)
SELECT g.id, CONCAT('S:', p.day_of_week, '-', p.period_no), NULL, NULL,
       p.day_of_week, p.period_no
FROM rule_group g
JOIN period p ON p.semester_id = g.semester_id
WHERE g.group_type = 'TIME'
  AND g.auto_filled = TRUE
  AND g.deleted_at IS NULL
  AND (
      (g.name = '上午' AND p.start_time < TIME '12:00:00')
      OR (g.name = '下午' AND p.start_time >= TIME '12:00:00')
  )
  AND NOT EXISTS (
      SELECT 1 FROM rule_group_member m
      WHERE m.group_id = g.id
        AND m.member_key = CONCAT('S:', p.day_of_week, '-', p.period_no)
  );
