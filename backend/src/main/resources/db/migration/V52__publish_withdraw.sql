-- 票 14（发布撤回）：发布行增加状态，撤回只改状态、不删历史。
-- 已有发布行默认回填 ACTIVE；历史重复发布只保留每学期最新一行 ACTIVE，
-- 否则撤回最新行后旧行会被误当成生效基线。
ALTER TABLE timetable_publish ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE timetable_publish ADD COLUMN withdrawn_at TIMESTAMP;
ALTER TABLE timetable_publish ADD COLUMN withdrawn_by VARCHAR(64);

UPDATE timetable_publish
SET status = 'WITHDRAWN'
WHERE status = 'ACTIVE'
  AND id NOT IN (
      SELECT latest_id FROM (
          SELECT MAX(id) AS latest_id FROM timetable_publish GROUP BY semester_id
      ) latest
  );

-- 撤回会删除本学期 timetable_entry 重建草稿；调代历史必须留下来。
-- 原来的 ON DELETE CASCADE 会在删 entry 时把申请/生效行一起删光：
-- 先放宽可空，再把外键改成 ON DELETE SET NULL（行保留、entry_id 置空）。
ALTER TABLE substitution_request DROP CONSTRAINT fk_sub_req_entry;
ALTER TABLE substitution_request ALTER COLUMN entry_id DROP NOT NULL;
ALTER TABLE substitution_request ADD CONSTRAINT fk_sub_req_entry
    FOREIGN KEY (entry_id) REFERENCES timetable_entry (id) ON DELETE SET NULL;

ALTER TABLE substitution DROP CONSTRAINT fk_sub_entry;
ALTER TABLE substitution ALTER COLUMN entry_id DROP NOT NULL;
ALTER TABLE substitution ADD CONSTRAINT fk_sub_entry
    FOREIGN KEY (entry_id) REFERENCES timetable_entry (id) ON DELETE SET NULL;

CREATE INDEX idx_publish_semester_status ON timetable_publish (semester_id, status, published_at);
