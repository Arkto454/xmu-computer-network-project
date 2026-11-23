CREATE TABLE IF NOT EXISTS student_user (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    app_id VARCHAR(64) NOT NULL,
    wechat_openid VARCHAR(128) NOT NULL,
    nickname VARCHAR(64) NOT NULL,
    contact VARCHAR(128),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_student_wechat_identity UNIQUE (app_id, wechat_openid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS errand_task (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    publisher_id BIGINT NOT NULL,
    title VARCHAR(100) NOT NULL,
    task_type VARCHAR(32) NOT NULL,
    pickup_location VARCHAR(200) NOT NULL,
    delivery_location VARCHAR(200) NOT NULL,
    deadline_at DATETIME(6) NOT NULL,
    reward_amount DECIMAL(10,2) NOT NULL,
    description VARCHAR(1000),
    publisher_phone VARCHAR(16) NOT NULL,
    publisher_wechat VARCHAR(64),
    private_instructions VARCHAR(1000),
    status VARCHAR(32) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_task_publisher FOREIGN KEY (publisher_id) REFERENCES student_user(id),
    CONSTRAINT ck_task_reward_positive CHECK (reward_amount > 0),
    INDEX idx_task_hall (status, created_at, id),
    INDEX idx_task_hall_type (status, task_type, created_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS task_assignment (
    task_id BIGINT NOT NULL PRIMARY KEY,
    runner_id BIGINT NOT NULL,
    runner_phone VARCHAR(16) NOT NULL,
    runner_wechat VARCHAR(64),
    accepted_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_assignment_task FOREIGN KEY (task_id) REFERENCES errand_task(id) ON DELETE CASCADE,
    CONSTRAINT fk_assignment_runner FOREIGN KEY (runner_id) REFERENCES student_user(id),
    INDEX idx_assignment_runner (runner_id, accepted_at, task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS task_claim_request (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    task_id BIGINT NOT NULL,
    student_id BIGINT NOT NULL,
    request_id CHAR(36) NOT NULL,
    payload_digest CHAR(64) NOT NULL,
    outcome VARCHAR(32) NOT NULL,
    closes_at DATETIME(6),
    runner_phone VARCHAR(16),
    runner_wechat VARCHAR(64),
    created_at DATETIME(6) NOT NULL,
    resolved_at DATETIME(6),
    CONSTRAINT uk_claim_actor_request UNIQUE (student_id, request_id),
    CONSTRAINT fk_claim_task FOREIGN KEY (task_id) REFERENCES errand_task(id) ON DELETE CASCADE,
    CONSTRAINT fk_claim_student FOREIGN KEY (student_id) REFERENCES student_user(id),
    INDEX idx_claim_task_outcome (task_id, outcome, id),
    INDEX idx_claim_due (outcome, closes_at, task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS task_pickup (
    task_id BIGINT NOT NULL PRIMARY KEY,
    picked_up_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_pickup_assignment FOREIGN KEY (task_id) REFERENCES task_assignment(task_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS task_cancellation (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    task_id BIGINT NOT NULL,
    requester_id BIGINT NOT NULL,
    reason VARCHAR(500),
    status VARCHAR(32) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    student_action VARCHAR(16),
    student_action_by BIGINT,
    student_action_at DATETIME(6),
    reviewer_id BIGINT,
    review_reason VARCHAR(500),
    reviewed_at DATETIME(6),
    CONSTRAINT fk_cancellation_assignment FOREIGN KEY (task_id) REFERENCES task_assignment(task_id) ON DELETE CASCADE,
    CONSTRAINT fk_cancellation_student FOREIGN KEY (requester_id) REFERENCES student_user(id),
    INDEX idx_cancellation_task (task_id, id),
    INDEX idx_cancellation_review (status, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS task_cancellation_request_key (
    student_id BIGINT NOT NULL,
    request_id CHAR(36) NOT NULL,
    cancellation_id BIGINT NOT NULL,
    PRIMARY KEY (student_id, request_id),
    CONSTRAINT fk_cancellation_request_key FOREIGN KEY (cancellation_id) REFERENCES task_cancellation(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS task_delivery_code (
    task_id BIGINT NOT NULL PRIMARY KEY,
    code_version BIGINT NOT NULL,
    payload_digest CHAR(64) NOT NULL,
    image_png BLOB,
    expires_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_delivery_assignment FOREIGN KEY (task_id) REFERENCES task_assignment(task_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS task_settlement (
    task_id BIGINT NOT NULL PRIMARY KEY,
    runner_id BIGINT NOT NULL,
    amount DECIMAL(10,2) NOT NULL,
    settled_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_settlement_assignment FOREIGN KEY (task_id) REFERENCES task_assignment(task_id) ON DELETE CASCADE,
    CONSTRAINT fk_settlement_runner FOREIGN KEY (runner_id) REFERENCES student_user(id),
    CONSTRAINT ck_settlement_amount_positive CHECK (amount > 0),
    INDEX idx_settlement_runner (runner_id, settled_at, task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
