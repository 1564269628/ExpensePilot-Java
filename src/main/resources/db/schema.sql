CREATE TABLE IF NOT EXISTS expense_task (
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
);

CREATE TABLE IF NOT EXISTS agent_step (
  id BIGINT PRIMARY KEY,
  task_id BIGINT NOT NULL,
  step_key VARCHAR(128) NOT NULL,
  step_type VARCHAR(64) NOT NULL,
  status VARCHAR(32) NOT NULL,
  depends_on_json JSON,
  input_json JSON,
  output_json JSON,
  error_code VARCHAR(64),
  error_message TEXT,
  retry_count INT NOT NULL DEFAULT 0,
  started_at DATETIME,
  finished_at DATETIME,
  UNIQUE KEY uk_task_step(task_id, step_key),
  KEY idx_task_status(task_id, status)
);

CREATE TABLE IF NOT EXISTS agent_checkpoint (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  task_id BIGINT NOT NULL,
  checkpoint_no BIGINT NOT NULL,
  node_name VARCHAR(64) NOT NULL,
  state_json JSON NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_task_checkpoint(task_id, checkpoint_no)
);

CREATE TABLE IF NOT EXISTS tool_execution_record (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  task_id BIGINT NOT NULL,
  tool_name VARCHAR(128) NOT NULL,
  operation_type VARCHAR(64) NOT NULL,
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
  KEY idx_request_id(request_id)
);

CREATE TABLE IF NOT EXISTS outbox_event (
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
);

CREATE TABLE IF NOT EXISTS consumed_event (
  event_id VARCHAR(128) PRIMARY KEY,
  consumer_group VARCHAR(128) NOT NULL,
  consumed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);


CREATE TABLE IF NOT EXISTS approval_record (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  task_id BIGINT NOT NULL,
  operation_type VARCHAR(64) NOT NULL,
  status VARCHAR(32) NOT NULL,
  approver VARCHAR(128),
  comment_text VARCHAR(500),
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  decided_at DATETIME,
  UNIQUE KEY uk_task_operation(task_id, operation_type)
);
