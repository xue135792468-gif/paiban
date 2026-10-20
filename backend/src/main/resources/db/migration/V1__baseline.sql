-- Baseline schema marker. Business tables are added by later migrations.
CREATE TABLE app_meta (
    meta_key   VARCHAR(100) PRIMARY KEY,
    meta_value VARCHAR(500) NOT NULL
);

INSERT INTO app_meta (meta_key, meta_value) VALUES ('schema.version', '1');
