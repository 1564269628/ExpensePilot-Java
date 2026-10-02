CREATE TABLE expense_task (
  id BIGINT PRIMARY KEY,
  user_id VARCHAR(64) NOT NULL,
  request_text TEXT NOT NULL,
  status VARCHAR(32) NOT NULL,
  current_node VARCHAR(64),
  thread_id VARCHAR(128) NOT NULL,
  last_error TEXT,
  retry_count INT NOT NULL DEFAULT 0,
  version INT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_status_updated(status, updated_at),
  UNIQUE KEY uk_thread_id(thread_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE agent_plan (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  task_id BIGINT NOT NULL,
  goal VARCHAR(1000),
  trip_scope_json JSON NOT NULL,
  needs_clarification BOOLEAN NOT NULL DEFAULT FALSE,
  clarification_questions_json JSON NOT NULL,
  plan_summary TEXT,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_agent_plan_task(task_id),
  CONSTRAINT fk_agent_plan_task FOREIGN KEY(task_id) REFERENCES expense_task(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE agent_step (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  task_id BIGINT NOT NULL,
  step_key VARCHAR(128) NOT NULL,
  step_type VARCHAR(64) NOT NULL,
  status VARCHAR(32) NOT NULL,
  depends_on_json JSON NOT NULL,
  input_json JSON NOT NULL,
  output_json JSON,
  error_code VARCHAR(64),
  error_message TEXT,
  retry_count INT NOT NULL DEFAULT 0,
  started_at DATETIME,
  finished_at DATETIME,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_task_step(task_id, step_key),
  KEY idx_task_status(task_id, status),
  CONSTRAINT fk_agent_step_task FOREIGN KEY(task_id) REFERENCES expense_task(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE tool_execution_record (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  task_id BIGINT NOT NULL,
  tool_name VARCHAR(128) NOT NULL,
  operation_type VARCHAR(128) NOT NULL,
  idempotency_key VARCHAR(190) NOT NULL,
  request_id VARCHAR(128),
  external_business_no VARCHAR(128),
  status VARCHAR(32) NOT NULL,
  request_json JSON,
  response_json JSON,
  last_error TEXT,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_idempotency(idempotency_key),
  KEY idx_request_id(request_id),
  KEY idx_tool_task(task_id, tool_name),
  CONSTRAINT fk_tool_execution_task FOREIGN KEY(task_id) REFERENCES expense_task(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE outbox_event (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  event_id VARCHAR(128) NOT NULL,
  aggregate_id VARCHAR(128) NOT NULL,
  event_type VARCHAR(128) NOT NULL,
  payload_json JSON NOT NULL,
  status VARCHAR(32) NOT NULL,
  retry_count INT NOT NULL DEFAULT 0,
  next_retry_at DATETIME,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  sent_at DATETIME,
  UNIQUE KEY uk_event_id(event_id),
  KEY idx_outbox_status(status, next_retry_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE consumed_event (
  event_id VARCHAR(128) NOT NULL,
  consumer_group VARCHAR(128) NOT NULL,
  consumed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(event_id, consumer_group)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE approval_record (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  task_id BIGINT NOT NULL,
  operation_type VARCHAR(64) NOT NULL,
  status VARCHAR(32) NOT NULL,
  approver VARCHAR(128),
  comment_text VARCHAR(500),
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  decided_at DATETIME,
  UNIQUE KEY uk_task_operation(task_id, operation_type),
  CONSTRAINT fk_approval_task FOREIGN KEY(task_id) REFERENCES expense_task(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
