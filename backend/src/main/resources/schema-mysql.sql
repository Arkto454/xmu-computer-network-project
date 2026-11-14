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
