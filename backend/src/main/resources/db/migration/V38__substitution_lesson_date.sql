-- 票 01（ADR-0030）：一次性调代按授课实例（学期 + 基线条目 + 原始授课日期）读取。
-- lesson_date 非空 = 新格式日期实例：只影响该笔授课日期本身；
-- lesson_date 为空 = 旧格式历史行：继续按「自 effective_date 起持续覆盖」读取，不做数据转换。
-- SWAP 行保留来源实例（lesson_date = 移出日），落点实例单独记在 target_lesson_date。

ALTER TABLE substitution ADD COLUMN lesson_date DATE NULL;
ALTER TABLE substitution ADD COLUMN target_lesson_date DATE NULL;

-- 新格式行以 lesson_date 为实例日期；SWAP 行的 effective_date 仍是来源日（= lesson_date），
-- 目标日在以后各周时需要靠 target_lesson_date 命中，因此索引按 semester + lesson_date 建。
CREATE INDEX idx_sub_lesson_date ON substitution (semester_id, lesson_date);
