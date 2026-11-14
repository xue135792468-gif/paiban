-- 票 03（03-A2）：账号登录类型。密码总钥匙与钉钉免登账号是两类账号，不能再靠「密码是否为空」猜测。
--
-- 升级语义：
--   * 所有既有账号一律 PASSWORD，原 password_hash 逐字保留（本迁移不改写任何旧凭据）。
--   * login_type 带 NOT NULL DEFAULT 'PASSWORD'：旧代码 / 升级脚本里不带该列的 raw INSERT
--     仍会落到 PASSWORD，不会被本迁移打断。
--   * DINGTALK 账号没有密码：哈希列放开 NOT NULL，实体允许 null；组合由 CHECK 兜底，
--     避免「钉钉账号带着哈希」或「密码账号没有哈希」这类半写状态。

alter table app_user add column login_type varchar(16) not null default 'PASSWORD';

alter table app_user alter column password_hash drop not null;

alter table app_user add constraint ck_app_user_login_type
    check (login_type in ('PASSWORD', 'DINGTALK'));

alter table app_user add constraint ck_app_user_hash_matches_type
    check ((login_type = 'DINGTALK' and password_hash is null)
        or (login_type = 'PASSWORD' and password_hash is not null));
