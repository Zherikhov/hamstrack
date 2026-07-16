package com.hamstrack.issue.entity;

/**
 * Custom field type. Determines the JSONB value shape in
 * {@code issue_field_values} and validation in {@code FieldValueService}:
 * TEXT/TEXTAREA/URL → string; NUMBER → number (config min/max);
 * DATE → "YYYY-MM-DD" string; SELECT → option id string;
 * MULTI_SELECT → array of option ids; USER → user UUID string
 * (workspace member); CHECKBOX → boolean.
 */
public enum FieldType {
    TEXT, TEXTAREA, NUMBER, DATE, SELECT, MULTI_SELECT, USER, CHECKBOX, URL
}
