-- Backfill notification_log.platform for rows written before ticket 04 added the column.
UPDATE notification_log SET platform = 'dingtalk' WHERE platform IS NULL AND channel = 'dingtalk';
UPDATE notification_log SET platform = 'system' WHERE platform IS NULL AND channel IN ('local','system');
UPDATE notification_log SET platform = channel WHERE platform IS NULL AND channel NOT IN ('dingtalk','local','system');
