package com.ironside.challengesphere.model;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

@Converter
public class StringListConverter implements AttributeConverter<List<String>, String> {
    @Override
    public String convertToDatabaseColumn(List<String> values) {
        if (values == null) {
            return null;
        }
        return values.stream()
                .map(StringListConverter::normalize)
                .filter(value -> !value.isBlank())
                .distinct()
                .reduce((left, right) -> left + "," + right)
                .orElse(null);
    }

    @Override
    public List<String> convertToEntityAttribute(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(","))
                .map(StringListConverter::normalize)
                .filter(item -> !item.isBlank())
                .distinct()
                .toList();
    }

    private static String normalize(String value) {
        return value == null
                ? ""
                : value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "-");
    }
}
