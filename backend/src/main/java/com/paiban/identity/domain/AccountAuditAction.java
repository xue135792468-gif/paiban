package com.paiban.identity.domain;

/**
 * 票 02：账号类审计事件的动作目录（9 类）。
 *
 * <p>后三类是给后续票据预留的写入入口：03 自动开号 / 钉钉配对，04 同步停用。
 * 票 02 只把它们列进目录供筛选与翻译，不提前实现任何自动流程。
 */
public enum AccountAuditAction {

    CREATE("ACCOUNT_CREATE", "手工创建账号"),
    UPDATE("ACCOUNT_UPDATE", "修改账号"),
    RESET_PASSWORD("ACCOUNT_RESET_PASSWORD", "重置密码"),
    DISABLE("ACCOUNT_DISABLE", "停用账号"),
    ENABLE("ACCOUNT_ENABLE", "启用账号"),
    IMPORT("ACCOUNT_IMPORT", "批量导入账号"),
    DINGTALK_AUTO_PROVISION("ACCOUNT_DINGTALK_AUTO_PROVISION", "钉钉自动开号"),
    DINGTALK_LINK("ACCOUNT_DINGTALK_LINK", "钉钉配对"),
    SYNC_DISABLE("ACCOUNT_SYNC_DISABLE", "同步停用");

    private final String code;
    private final String label;

    AccountAuditAction(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String code() {
        return code;
    }

    public String label() {
        return label;
    }

    /** 未知编码返回 null：筛选参数按「没有这个动作」处理，由调用方决定拒绝还是忽略。 */
    public static AccountAuditAction byCode(String code) {
        for (AccountAuditAction action : values()) {
            if (action.code.equals(code)) {
                return action;
            }
        }
        return null;
    }
}
