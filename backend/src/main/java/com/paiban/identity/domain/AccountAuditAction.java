package com.paiban.identity.domain;

import java.util.List;

/**
 * 票 02：账号类审计事件的动作目录（10 类）。
 *
 * <p>后三类是给后续票据预留的写入入口：03 自动开号 / 钉钉配对，04 同步停用。
 * 票 02 只把它们列进目录供筛选与翻译，不提前实现任何自动流程。
 *
 * <p>升级前的账号事件用的是旧动作码（{@code DISABLE_USER} / {@code ENABLE_USER} / {@code IMPORT}）。
 * 这些旧码只在读取时做只读别名：库里的原值不动，DTO 一律返回规范码，动作筛选同时匹配两者。
 * 只读别名仅用于 {@code ACCOUNT} 分类，其他分类里的 {@code IMPORT} 不受影响。
 */
public enum AccountAuditAction {

    CREATE("ACCOUNT_CREATE", "手工创建账号"),
    ROLE_CHANGE("ACCOUNT_ROLE_CHANGE", "改身份"),
    UPDATE("ACCOUNT_UPDATE", "修改资料"),
    RESET_PASSWORD("ACCOUNT_RESET_PASSWORD", "重置密码"),
    DISABLE("ACCOUNT_DISABLE", "停用账号"),
    ENABLE("ACCOUNT_ENABLE", "启用账号"),
    IMPORT("ACCOUNT_IMPORT", "批量导入账号"),
    DINGTALK_AUTO_PROVISION("ACCOUNT_DINGTALK_AUTO_PROVISION", "钉钉自动开号"),
    DINGTALK_LINK("ACCOUNT_DINGTALK_LINK", "钉钉配对"),
    SYNC_DISABLE("ACCOUNT_SYNC_DISABLE", "同步停用");

    private final String code;
    private final String label;

    /** 旧动作码 → 规范码；只读映射，不改写历史行。 */
    private static final List<LegacyAlias> LEGACY_ALIASES = List.of(
            new LegacyAlias("DISABLE_USER", DISABLE.code),
            new LegacyAlias("ENABLE_USER", ENABLE.code),
            new LegacyAlias("IMPORT", IMPORT.code));

    private record LegacyAlias(String legacyCode, String canonicalCode) {}

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

    /**
     * 历史行读取时用的规范码：已知旧码映射到规范码，其余（含未知历史码）原样返回，
     * 由页面给中文兜底。
     */
    public static String canonicalCode(String storedCode) {
        return LEGACY_ALIASES.stream()
                .filter(alias -> alias.legacyCode.equals(storedCode))
                .map(LegacyAlias::canonicalCode)
                .findFirst()
                .orElse(storedCode);
    }

    /** 规范码对应的旧码；没有旧码时返回 null。 {@code IMPORT} 的别名只用于 ACCOUNT 分类查询。 */
    public static String legacyCodeFor(String canonicalCode) {
        return LEGACY_ALIASES.stream()
                .filter(alias -> alias.canonicalCode.equals(canonicalCode))
                .map(LegacyAlias::legacyCode)
                .findFirst()
                .orElse(null);
    }
}
