-- Durable local workspace. This is not a connector to real corporate systems.
-- Production MCP adapters remain separate and unchanged.
CREATE TABLE local_document (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  document_ref VARCHAR(160) NOT NULL,
  user_id VARCHAR(64) NOT NULL,
  source_type VARCHAR(16) NOT NULL,
  file_name VARCHAR(255) NOT NULL,
  file_path VARCHAR(512),
  category VARCHAR(24) NOT NULL,
  amount DECIMAL(13,2) NOT NULL,
  city VARCHAR(80) NOT NULL,
  issued_on DATE NOT NULL,
  is_sample BOOLEAN NOT NULL DEFAULT FALSE,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_local_document_ref(document_ref),
  KEY idx_local_document_user(user_id,issued_on,city)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE local_trip (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id VARCHAR(64) NOT NULL,
  city VARCHAR(80) NOT NULL,
  start_date DATE NOT NULL,
  end_date DATE NOT NULL,
  purpose VARCHAR(255) NOT NULL,
  is_sample BOOLEAN NOT NULL DEFAULT FALSE,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_local_trip_user(user_id,start_date,end_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE local_policy_rule (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  city VARCHAR(80) NOT NULL,
  category VARCHAR(24) NOT NULL,
  max_amount DECIMAL(13,2) NOT NULL,
  description VARCHAR(255) NOT NULL,
  UNIQUE KEY uk_local_policy(city,category)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Seeded local-demo limits; never pretend they represent a real company's policies.
INSERT INTO local_policy_rule(city,category,max_amount,description) VALUES
('*','HOTEL',600.00,'LOCAL DEMO: single hotel receipt limit'),
('*','TRANSPORT',2000.00,'LOCAL DEMO: single transportation receipt limit'),
('*','MEAL',200.00,'LOCAL DEMO: single meal receipt limit'),
('*','OTHER',1000.00,'LOCAL DEMO: single other-expense receipt limit'),
('上海','HOTEL',600.00,'LOCAL DEMO: Shanghai hotel receipt limit'),
('杭州','HOTEL',500.00,'LOCAL DEMO: Hangzhou hotel receipt limit');

CREATE TABLE local_expense_submission (
  request_id VARCHAR(128) NOT NULL PRIMARY KEY,
  user_id VARCHAR(64) NOT NULL,
  business_no VARCHAR(128) NOT NULL,
  draft_json JSON NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_local_business_no(business_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE local_notification (
  request_id VARCHAR(128) NOT NULL PRIMARY KEY,
  user_id VARCHAR(64) NOT NULL,
  business_no VARCHAR(128) NOT NULL,
  event_id VARCHAR(128) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_local_notification_event(event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
