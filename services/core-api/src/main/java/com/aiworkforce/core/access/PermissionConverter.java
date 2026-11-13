package com.aiworkforce.core.access;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Stores {@link Permission} by its stable code (not the enum name). */
@Converter
public class PermissionConverter implements AttributeConverter<Permission, String> {

    @Override
    public String convertToDatabaseColumn(Permission permission) {
        return permission == null ? null : permission.code();
    }

    @Override
    public Permission convertToEntityAttribute(String code) {
        return code == null ? null : Permission.fromCode(code);
    }
}
