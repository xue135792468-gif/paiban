package com.paiban.ai.domain;

import com.paiban.ai.AiTaskType;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Stores the enum with the lower-case spelling used by the database and API. */
@Converter
public class AiTaskTypeConverter implements AttributeConverter<AiTaskType, String> {

    @Override
    public String convertToDatabaseColumn(AiTaskType attribute) {
        return attribute == null ? null : attribute.value();
    }

    @Override
    public AiTaskType convertToEntityAttribute(String dbData) {
        return dbData == null ? null : AiTaskType.fromValue(dbData);
    }
}
