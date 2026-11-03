package com.paiban.common.deletion;

import com.paiban.common.web.ApiException;
import java.util.Locale;
import org.springframework.util.StringUtils;

/**
 * {@code DELETE} removes only the requested entity and is refused when anything
 * would be orphaned; {@code CASCADE} also removes the documented dependants in the
 * same batch. Disabling is a separate endpoint and never cascades.
 */
public enum DeleteMode {
    DELETE,
    CASCADE;

    public static DeleteMode parse(String value) {
        if (!StringUtils.hasText(value)) {
            return DELETE;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw ApiException.badRequest("不支持的删除方式: " + value);
        }
    }
}
