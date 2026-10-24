package com.paiban.integration.domain;

/**
 * A DingTalk department as returned by {@code topapi/v2/department/listsub}.
 *
 * <p>Negative ids (for example {@code -7}, the 家校通讯录) are special departments
 * that must never be imported into the teacher table.
 */
public record DingDepartment(long id, String name, Long parentId) {

    public boolean isSpecial() {
        return id < 0;
    }
}
