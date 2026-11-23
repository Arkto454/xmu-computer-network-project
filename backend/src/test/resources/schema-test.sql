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
);

CREATE TABLE IF NOT EXISTS errand_task (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    publisher_id BIGINT NOT NULL,
    title VARCHAR(100) NOT NULL,
    task_type VARCHAR(32) NOT NULL,
    pickup_location VARCHAR(200) NOT NULL,
    delivery_location VARCHAR(200) NOT NULL,
    deadline_at TIMESTAMP(6) NOT NULL,
    reward_amount DECIMAL(10,2) NOT NULL,
    description VARCHAR(1000),
    publisher_phone VARCHAR(16) NOT NULL,
    publisher_wechat VARCHAR(64),
    private_instructions VARCHAR(1000),
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_task_publisher FOREIGN KEY (publisher_id) REFERENCES student_user(id),
    CONSTRAINT ck_task_reward_positive CHECK (reward_amount > 0)
);
CREATE INDEX IF NOT EXISTS idx_task_hall ON errand_task(status, created_at, id);
CREATE INDEX IF NOT EXISTS idx_task_hall_type ON errand_task(status, task_type, created_at, id);

CREATE TABLE IF NOT EXISTS task_assignment (
    task_id BIGINT NOT NULL PRIMARY KEY,
    runner_id BIGINT NOT NULL,
    runner_phone VARCHAR(16) NOT NULL,
    runner_wechat VARCHAR(64),
    accepted_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_assignment_task FOREIGN KEY (task_id) REFERENCES errand_task(id) ON DELETE CASCADE,
    CONSTRAINT fk_assignment_runner FOREIGN KEY (runner_id) REFERENCES student_user(id)
);
CREATE INDEX IF NOT EXISTS idx_assignment_runner ON task_assignment(runner_id, accepted_at, task_id);

CREATE TABLE IF NOT EXISTS task_claim_request (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    task_id BIGINT NOT NULL,
    student_id BIGINT NOT NULL,
    request_id CHAR(36) NOT NULL,
    payload_digest CHAR(64) NOT NULL,
    outcome VARCHAR(32) NOT NULL,
    closes_at TIMESTAMP(6),
    runner_phone VARCHAR(16),
    runner_wechat VARCHAR(64),
    created_at TIMESTAMP(6) NOT NULL,
    resolved_at TIMESTAMP(6),
    CONSTRAINT uk_claim_actor_request UNIQUE (student_id, request_id),
    CONSTRAINT fk_claim_task FOREIGN KEY (task_id) REFERENCES errand_task(id) ON DELETE CASCADE,
    CONSTRAINT fk_claim_student FOREIGN KEY (student_id) REFERENCES student_user(id)
);
CREATE INDEX IF NOT EXISTS idx_claim_task_outcome ON task_claim_request(task_id, outcome, id);
CREATE INDEX IF NOT EXISTS idx_claim_due ON task_claim_request(outcome, closes_at, task_id);

CREATE TABLE IF NOT EXISTS task_pickup (
    task_id BIGINT NOT NULL PRIMARY KEY,
    picked_up_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_pickup_assignment FOREIGN KEY (task_id) REFERENCES task_assignment(task_id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS task_cancellation (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    task_id BIGINT NOT NULL,
    requester_id BIGINT NOT NULL,
    reason VARCHAR(500),
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    student_action VARCHAR(16),
    student_action_by BIGINT,
    student_action_at TIMESTAMP(6),
    reviewer_id BIGINT,
    review_reason VARCHAR(500),
    reviewed_at TIMESTAMP(6),
    CONSTRAINT fk_cancellation_assignment FOREIGN KEY (task_id) REFERENCES task_assignment(task_id) ON DELETE CASCADE,
    CONSTRAINT fk_cancellation_student FOREIGN KEY (requester_id) REFERENCES student_user(id)
);
CREATE INDEX IF NOT EXISTS idx_cancellation_task ON task_cancellation(task_id, id);
CREATE INDEX IF NOT EXISTS idx_cancellation_review ON task_cancellation(status, id);

CREATE TABLE IF NOT EXISTS task_cancellation_request_key (
    student_id BIGINT NOT NULL,
    request_id CHAR(36) NOT NULL,
    cancellation_id BIGINT NOT NULL,
    PRIMARY KEY (student_id, request_id),
    CONSTRAINT fk_cancellation_request_key FOREIGN KEY (cancellation_id) REFERENCES task_cancellation(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS task_delivery_code (
    task_id BIGINT NOT NULL PRIMARY KEY,
    code_version BIGINT NOT NULL,
    payload_digest CHAR(64) NOT NULL,
    image_png BLOB,
    expires_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_delivery_assignment FOREIGN KEY (task_id) REFERENCES task_assignment(task_id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS task_settlement (
    task_id BIGINT NOT NULL PRIMARY KEY,
    runner_id BIGINT NOT NULL,
    amount DECIMAL(10,2) NOT NULL,
    settled_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_settlement_assignment FOREIGN KEY (task_id) REFERENCES task_assignment(task_id) ON DELETE CASCADE,
    CONSTRAINT fk_settlement_runner FOREIGN KEY (runner_id) REFERENCES student_user(id),
    CONSTRAINT ck_settlement_amount_positive CHECK (amount > 0)
);
CREATE INDEX IF NOT EXISTS idx_settlement_runner ON task_settlement(runner_id, settled_at, task_id);
