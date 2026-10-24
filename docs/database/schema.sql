-- WeBox MySQL 8 schema proposal. Flyway will split/version this during implementation.
CREATE DATABASE IF NOT EXISTS webox
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_0900_ai_ci;
USE webox;

CREATE TABLE app_user (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  email VARCHAR(200) NOT NULL,
  password_hash VARCHAR(100) NOT NULL,
  display_name VARCHAR(100) NOT NULL,
  role VARCHAR(20) NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_app_user_email (email),
  CONSTRAINT ck_app_user_role CHECK (role IN ('EMPLOYEE', 'ADMIN'))
) ENGINE=InnoDB;

CREATE TABLE user_preference (
  user_id BIGINT UNSIGNED NOT NULL,
  spice_level VARCHAR(20) NULL,
  taste_intensity VARCHAR(20) NULL,
  budget_min DECIMAL(10,2) NULL,
  budget_max DECIMAL(10,2) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (user_id),
  CONSTRAINT fk_preference_user FOREIGN KEY (user_id) REFERENCES app_user (id),
  CONSTRAINT ck_preference_spice CHECK (spice_level IS NULL OR spice_level IN ('NONE', 'MILD', 'MEDIUM', 'HOT')),
  CONSTRAINT ck_preference_taste CHECK (taste_intensity IS NULL OR taste_intensity IN ('LIGHT', 'BALANCED', 'RICH')),
  CONSTRAINT ck_preference_budget CHECK (
    (budget_min IS NULL OR budget_min >= 0) AND
    (budget_max IS NULL OR budget_max >= 0) AND
    (budget_min IS NULL OR budget_max IS NULL OR budget_min <= budget_max)
  )
) ENGINE=InnoDB;

CREATE TABLE user_allergen (
  user_id BIGINT UNSIGNED NOT NULL,
  allergen VARCHAR(20) NOT NULL,
  PRIMARY KEY (user_id, allergen),
  CONSTRAINT fk_user_allergen_user FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE CASCADE,
  CONSTRAINT ck_user_allergen CHECK (allergen IN ('PEANUTS', 'DAIRY', 'EGG', 'GLUTEN', 'SOY', 'FISH', 'SHELLFISH'))
) ENGINE=InnoDB;

CREATE TABLE user_preferred_category (
  user_id BIGINT UNSIGNED NOT NULL,
  category VARCHAR(30) NOT NULL,
  PRIMARY KEY (user_id, category),
  CONSTRAINT fk_user_category_user FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE CASCADE,
  CONSTRAINT ck_user_category CHECK (category IN ('CHINESE', 'WESTERN', 'JAPANESE', 'LIGHT_MEAL', 'KOREAN', 'SOUTHEAST_ASIAN'))
) ENGINE=InnoDB;

CREATE TABLE user_address (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id BIGINT UNSIGNED NOT NULL,
  label VARCHAR(50) NULL,
  address_text VARCHAR(200) NOT NULL,
  is_default BOOLEAN NOT NULL DEFAULT FALSE,
  deleted_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_address_user_active (user_id, deleted_at),
  CONSTRAINT fk_address_user FOREIGN KEY (user_id) REFERENCES app_user (id)
) ENGINE=InnoDB;

CREATE TABLE dish (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  name VARCHAR(120) NOT NULL,
  description VARCHAR(1000) NOT NULL,
  base_price DECIMAL(10,2) NOT NULL,
  category VARCHAR(30) NOT NULL,
  protein_source VARCHAR(100) NOT NULL,
  spice_level VARCHAR(20) NOT NULL,
  image_url VARCHAR(500) NOT NULL,
  published BOOLEAN NOT NULL DEFAULT TRUE,
  version BIGINT UNSIGNED NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_dish_filter (published, category, name),
  CONSTRAINT ck_dish_price CHECK (base_price >= 0),
  CONSTRAINT ck_dish_category CHECK (category IN ('CHINESE', 'WESTERN', 'JAPANESE', 'LIGHT_MEAL', 'KOREAN', 'SOUTHEAST_ASIAN')),
  CONSTRAINT ck_dish_spice CHECK (spice_level IN ('NONE', 'MILD', 'MEDIUM', 'HOT'))
) ENGINE=InnoDB;

CREATE TABLE dish_allergen (
  dish_id BIGINT UNSIGNED NOT NULL,
  allergen VARCHAR(20) NOT NULL,
  PRIMARY KEY (dish_id, allergen),
  CONSTRAINT fk_dish_allergen_dish FOREIGN KEY (dish_id) REFERENCES dish (id) ON DELETE CASCADE,
  CONSTRAINT ck_dish_allergen CHECK (allergen IN ('PEANUTS', 'DAIRY', 'EGG', 'GLUTEN', 'SOY', 'FISH', 'SHELLFISH'))
) ENGINE=InnoDB;

CREATE TABLE dish_option_group (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  dish_id BIGINT UNSIGNED NOT NULL,
  name VARCHAR(80) NOT NULL,
  selection_type VARCHAR(20) NOT NULL,
  required_selection BOOLEAN NOT NULL DEFAULT FALSE,
  min_selections SMALLINT UNSIGNED NOT NULL DEFAULT 0,
  max_selections SMALLINT UNSIGNED NOT NULL DEFAULT 1,
  display_order SMALLINT UNSIGNED NOT NULL DEFAULT 0,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  PRIMARY KEY (id),
  KEY idx_option_group_dish (dish_id, active, display_order),
  CONSTRAINT fk_option_group_dish FOREIGN KEY (dish_id) REFERENCES dish (id),
  CONSTRAINT ck_option_group_type CHECK (selection_type IN ('SINGLE', 'MULTIPLE')),
  CONSTRAINT ck_option_group_range CHECK (min_selections <= max_selections AND max_selections <= 20),
  CONSTRAINT ck_option_group_required CHECK (required_selection = FALSE OR min_selections >= 1)
) ENGINE=InnoDB;

CREATE TABLE dish_option_value (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  option_group_id BIGINT UNSIGNED NOT NULL,
  name VARCHAR(80) NOT NULL,
  price_delta DECIMAL(10,2) NOT NULL DEFAULT 0.00,
  display_order SMALLINT UNSIGNED NOT NULL DEFAULT 0,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  PRIMARY KEY (id),
  UNIQUE KEY uk_option_value_name (option_group_id, name),
  KEY idx_option_value_group (option_group_id, active, display_order),
  CONSTRAINT fk_option_value_group FOREIGN KEY (option_group_id) REFERENCES dish_option_group (id),
  CONSTRAINT ck_option_value_price CHECK (price_delta >= 0)
) ENGINE=InnoDB;

CREATE TABLE daily_menu (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  menu_date DATE NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
  publication_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
  published_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_daily_menu_date (menu_date),
  CONSTRAINT ck_daily_menu_status CHECK (status IN ('DRAFT', 'PUBLISHED'))
) ENGINE=InnoDB;

CREATE TABLE daily_menu_item (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  daily_menu_id BIGINT UNSIGNED NOT NULL,
  dish_id BIGINT UNSIGNED NOT NULL,
  initial_stock INT UNSIGNED NOT NULL,
  remaining_stock INT UNSIGNED NOT NULL,
  visible BOOLEAN NOT NULL DEFAULT TRUE,
  stock_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_daily_menu_dish (daily_menu_id, dish_id),
  KEY idx_menu_item_stock (daily_menu_id, remaining_stock, visible),
  CONSTRAINT fk_menu_item_menu FOREIGN KEY (daily_menu_id) REFERENCES daily_menu (id),
  CONSTRAINT fk_menu_item_dish FOREIGN KEY (dish_id) REFERENCES dish (id),
  CONSTRAINT ck_menu_item_stock CHECK (remaining_stock <= initial_stock)
) ENGINE=InnoDB;

CREATE TABLE customer_order (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  order_number VARCHAR(32) NOT NULL,
  user_id BIGINT UNSIGNED NOT NULL,
  delivery_date DATE NOT NULL,
  meal_slot VARCHAR(20) NOT NULL,
  delivery_address VARCHAR(200) NOT NULL,
  status VARCHAR(20) NOT NULL,
  total_amount DECIMAL(10,2) NOT NULL,
  active_slot_guard TINYINT AS (CASE WHEN status IN ('PENDING', 'CONFIRMED') THEN 1 ELSE NULL END) STORED,
  version BIGINT UNSIGNED NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  cancelled_at DATETIME(3) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_order_number (order_number),
  UNIQUE KEY uk_active_order_slot (user_id, delivery_date, meal_slot, active_slot_guard),
  KEY idx_order_user_created (user_id, created_at DESC),
  KEY idx_order_dashboard (delivery_date, status, meal_slot),
  CONSTRAINT fk_order_user FOREIGN KEY (user_id) REFERENCES app_user (id),
  CONSTRAINT ck_order_slot CHECK (meal_slot IN ('LUNCH', 'DINNER')),
  CONSTRAINT ck_order_status CHECK (status IN ('PENDING', 'CONFIRMED', 'COMPLETED', 'CANCELLED')),
  CONSTRAINT ck_order_total CHECK (total_amount >= 0)
) ENGINE=InnoDB;

CREATE TABLE order_item (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  order_id BIGINT UNSIGNED NOT NULL,
  daily_menu_item_id BIGINT UNSIGNED NOT NULL,
  dish_id BIGINT UNSIGNED NOT NULL,
  dish_name_snapshot VARCHAR(120) NOT NULL,
  base_price_snapshot DECIMAL(10,2) NOT NULL,
  unit_price DECIMAL(10,2) NOT NULL,
  quantity SMALLINT UNSIGNED NOT NULL,
  subtotal DECIMAL(10,2) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_order_item_order (order_id),
  KEY idx_order_item_dish_reporting (dish_id, order_id),
  CONSTRAINT fk_order_item_order FOREIGN KEY (order_id) REFERENCES customer_order (id),
  CONSTRAINT fk_order_item_menu_item FOREIGN KEY (daily_menu_item_id) REFERENCES daily_menu_item (id),
  CONSTRAINT fk_order_item_dish FOREIGN KEY (dish_id) REFERENCES dish (id),
  CONSTRAINT ck_order_item_quantity CHECK (quantity BETWEEN 1 AND 5),
  CONSTRAINT ck_order_item_money CHECK (base_price_snapshot >= 0 AND unit_price >= 0 AND subtotal >= 0)
) ENGINE=InnoDB;

CREATE TABLE order_status_history (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  order_id BIGINT UNSIGNED NOT NULL,
  from_status VARCHAR(20) NULL,
  to_status VARCHAR(20) NOT NULL,
  actor_user_id BIGINT UNSIGNED NOT NULL,
  actor_role VARCHAR(20) NOT NULL,
  reason VARCHAR(500) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_order_status_history_order (order_id, created_at),
  CONSTRAINT fk_order_status_history_order FOREIGN KEY (order_id) REFERENCES customer_order (id),
  CONSTRAINT fk_order_status_history_actor FOREIGN KEY (actor_user_id) REFERENCES app_user (id),
  CONSTRAINT ck_order_status_history_from CHECK (from_status IS NULL OR from_status IN ('PENDING', 'CONFIRMED', 'COMPLETED', 'CANCELLED')),
  CONSTRAINT ck_order_status_history_to CHECK (to_status IN ('PENDING', 'CONFIRMED', 'COMPLETED', 'CANCELLED')),
  CONSTRAINT ck_order_status_history_role CHECK (actor_role IN ('EMPLOYEE', 'ADMIN'))
) ENGINE=InnoDB;

CREATE TABLE order_item_option (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  order_item_id BIGINT UNSIGNED NOT NULL,
  option_group_name_snapshot VARCHAR(80) NOT NULL,
  option_value_name_snapshot VARCHAR(80) NOT NULL,
  price_delta_snapshot DECIMAL(10,2) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_order_item_option_item (order_item_id),
  CONSTRAINT fk_order_option_item FOREIGN KEY (order_item_id) REFERENCES order_item (id) ON DELETE CASCADE,
  CONSTRAINT ck_order_option_price CHECK (price_delta_snapshot >= 0)
) ENGINE=InnoDB;

CREATE TABLE idempotency_record (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id BIGINT UNSIGNED NOT NULL,
  idempotency_key CHAR(36) NOT NULL,
  request_hash CHAR(64) NOT NULL,
  reservation_id CHAR(36) NULL,
  state VARCHAR(20) NOT NULL,
  order_id BIGINT UNSIGNED NULL,
  response_status SMALLINT UNSIGNED NULL,
  failure_code VARCHAR(100) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  completed_at DATETIME(3) NULL,
  expires_at DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_idempotency_user_key (user_id, idempotency_key),
  UNIQUE KEY uk_idempotency_reservation (reservation_id),
  KEY idx_idempotency_expiry (expires_at),
  CONSTRAINT fk_idempotency_user FOREIGN KEY (user_id) REFERENCES app_user (id),
  CONSTRAINT fk_idempotency_order FOREIGN KEY (order_id) REFERENCES customer_order (id),
  CONSTRAINT ck_idempotency_state CHECK (state IN ('PROCESSING', 'COMPLETED', 'FAILED'))
) ENGINE=InnoDB;

CREATE TABLE inventory_movement (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  daily_menu_item_id BIGINT UNSIGNED NOT NULL,
  order_id BIGINT UNSIGNED NOT NULL,
  movement_type VARCHAR(30) NOT NULL,
  quantity_delta INT NOT NULL,
  stock_after INT UNSIGNED NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_inventory_order_type_item (order_id, movement_type, daily_menu_item_id),
  KEY idx_inventory_menu_time (daily_menu_item_id, created_at),
  CONSTRAINT fk_inventory_menu_item FOREIGN KEY (daily_menu_item_id) REFERENCES daily_menu_item (id),
  CONSTRAINT fk_inventory_order FOREIGN KEY (order_id) REFERENCES customer_order (id),
  CONSTRAINT ck_inventory_type CHECK (movement_type IN ('ORDER_DEDUCT', 'CANCEL_RESTORE')),
  CONSTRAINT ck_inventory_delta CHECK (quantity_delta <> 0)
) ENGINE=InnoDB;

CREATE TABLE outbox_event (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  aggregate_type VARCHAR(50) NOT NULL,
  aggregate_id VARCHAR(100) NOT NULL,
  event_type VARCHAR(100) NOT NULL,
  payload JSON NOT NULL,
  occurred_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  published_at DATETIME(3) NULL,
  attempt_count INT UNSIGNED NOT NULL DEFAULT 0,
  next_attempt_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_outbox_unpublished (published_at, next_attempt_at, id)
) ENGINE=InnoDB;

