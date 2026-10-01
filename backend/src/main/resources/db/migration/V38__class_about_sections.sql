-- FR-13: ordered introduction sections; existing text/rules remain intact.
ALTER TABLE class_about ADD COLUMN sections_json MEDIUMTEXT NULL, ALGORITHM=INSTANT;
