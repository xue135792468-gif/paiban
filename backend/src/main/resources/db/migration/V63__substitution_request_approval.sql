-- 票 05：恢复老师自助代课的审批链（V55 于票 15 删除过审批列，这里按新语义加回）。
--
-- 语义（spec「调代课（代课申请与审批）」、t05-wire-contract）：
--   * 老师自助提交只落 SUBMITTED；批准后才走既有代课生效路径。
--   * 审批人 = 真实批准的管理员账号（app_user.id），不是其绑定的教师身份。
--   * 审批意见同时承载批准备注与驳回理由（驳回时必填，由服务层校验）。
--   * 不恢复审批模式切换（ADR-0009 的 approval_mode），工作台直接生效路径不变。
-- 只加不改：V1–V62 保持原样，本文件是 V63。

alter table substitution_request add column approver_user_id bigint;
alter table substitution_request add column approved_at timestamp;
alter table substitution_request add column approval_comment varchar(500);

alter table substitution_request add constraint fk_request_approver
    foreign key (approver_user_id) references app_user (id);

-- 待审批列表按「学期 + 状态」取数；状态筛选也是老师「我的调代」的常用条件。
create index idx_substitution_request_semester_status
    on substitution_request (semester_id, status);
