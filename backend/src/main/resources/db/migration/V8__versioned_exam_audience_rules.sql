ALTER TABLE exams
    ADD COLUMN audience_rule_version INT NOT NULL DEFAULT 1,
    ADD COLUMN audience_operator VARCHAR(8) NOT NULL DEFAULT 'AND';
