-- Fix CHAR(7) columns in issue_types and statuses — bpchar causes Hibernate schema validation
-- failures (expects varchar). VARCHAR(7) is equivalent for hex color values like '#6B7280'.
ALTER TABLE issue_types ALTER COLUMN color TYPE VARCHAR(7);
ALTER TABLE statuses    ALTER COLUMN color TYPE VARCHAR(7);
