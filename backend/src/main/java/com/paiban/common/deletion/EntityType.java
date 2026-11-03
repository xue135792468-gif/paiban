package com.paiban.common.deletion;

import com.paiban.common.web.ApiException;
import java.util.Locale;

/**
 * The entity families the lifecycle mechanism knows about. Wire values are the API
 * path words ({@code teacher}, {@code class}, …); stored values are the enum names.
 */
public enum EntityType {
    TEACHER("teacher", "教师"),
    CLASS("class", "行政班"),
    COURSE("course", "课程"),
    GRADE("grade", "年级"),
    SEMESTER("semester", "学期"),
    ASSIGNMENT("assignment", "授课任务");

    private final String wireName;

    private final String label;

    EntityType(String wireName, String label) {
        this.wireName = wireName;
        this.label = label;
    }

    public String wireName() {
        return wireName;
    }

    public String label() {
        return label;
    }

    public static EntityType fromWire(String value) {
        if (value != null) {
            for (EntityType type : values()) {
                if (type.wireName.equalsIgnoreCase(value)) {
                    return type;
                }
            }
        }
        throw ApiException.badRequest("不支持的实体类型: " + value);
    }

    public static EntityType fromStored(String value) {
        try {
            return valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw ApiException.badRequest("不支持的实体类型: " + value);
        }
    }
}
