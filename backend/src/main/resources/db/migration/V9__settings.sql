CREATE TABLE app_setting (
    setting_key   VARCHAR(100)  PRIMARY KEY,
    setting_value VARCHAR(2000),
    updated_at    TIMESTAMP     NOT NULL
);
