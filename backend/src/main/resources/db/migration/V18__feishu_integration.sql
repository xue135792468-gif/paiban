ALTER TABLE teacher ADD COLUMN feishu_user_id VARCHAR(100);
ALTER TABLE teacher ADD COLUMN feishu_open_id VARCHAR(100);
ALTER TABLE notification_log ADD COLUMN platform VARCHAR(20);
CREATE UNIQUE INDEX uk_teacher_feishu_user_id ON teacher (feishu_user_id);
CREATE INDEX idx_notification_log_platform ON notification_log (platform);
