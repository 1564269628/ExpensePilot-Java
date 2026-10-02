-- V2: API 创建幂等 + 支持更长的 OIDC subject
ALTER TABLE expense_task
    MODIFY COLUMN user_id VARCHAR(255) NOT NULL,
    ADD COLUMN client_request_hash CHAR(64) NULL AFTER user_id;

CREATE UNIQUE INDEX uk_expense_task_user_request
    ON expense_task(user_id, client_request_hash);
