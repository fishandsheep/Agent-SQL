-- shop.brand definition

CREATE TABLE `brand` (
  `brand_id` bigint NOT NULL AUTO_INCREMENT,
  `brand_name` varchar(128) DEFAULT NULL,
  PRIMARY KEY (`brand_id`)
) ENGINE=InnoDB AUTO_INCREMENT=101 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


-- shop.category definition

CREATE TABLE `category` (
  `category_id` bigint NOT NULL AUTO_INCREMENT,
  `category_name` varchar(128) DEFAULT NULL,
  `parent_id` bigint DEFAULT NULL,
  PRIMARY KEY (`category_id`),
  KEY `idx_parent` (`parent_id`)
) ENGINE=InnoDB AUTO_INCREMENT=101 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


-- shop.order_item definition

CREATE TABLE `order_item` (
  `item_id` bigint NOT NULL AUTO_INCREMENT,
  `order_id` bigint DEFAULT NULL,
  `product_id` bigint DEFAULT NULL,
  `quantity` int DEFAULT NULL,
  `price` decimal(10,2) DEFAULT NULL,
  PRIMARY KEY (`item_id`),
  KEY `idx_product_id` (`product_id`),
  KEY `idx_quantity_price` (`quantity`,`price`)
) ENGINE=InnoDB AUTO_INCREMENT=6143251 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


-- shop.orders definition

CREATE TABLE `orders` (
  `order_id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint DEFAULT NULL,
  `order_no` varchar(64) DEFAULT NULL,
  `total_amount` decimal(10,2) DEFAULT NULL,
  `status` tinyint DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `pay_time` datetime DEFAULT NULL,
  PRIMARY KEY (`order_id`),
  KEY `idx_user_time` (`user_id`,`create_time`),
  KEY `idx_status` (`status`),
  KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB AUTO_INCREMENT=3276401 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


-- shop.product definition

CREATE TABLE `product` (
  `product_id` bigint NOT NULL AUTO_INCREMENT,
  `product_name` varchar(255) DEFAULT NULL,
  `category_id` bigint DEFAULT NULL,
  `brand_id` bigint DEFAULT NULL,
  `price` decimal(10,2) DEFAULT NULL,
  `stock` int DEFAULT NULL,
  `status` tinyint DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  PRIMARY KEY (`product_id`),
  KEY `idx_category` (`category_id`),
  KEY `idx_price` (`price`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB AUTO_INCREMENT=2522836 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


-- shop.sql_analysis_history definition

CREATE TABLE `sql_analysis_history` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `original_sql` longtext NOT NULL,
  `optimized_sql` longtext,
  `best_plan_id` varchar(32) DEFAULT NULL,
  `strategy` varchar(128) DEFAULT NULL,
  `improvement_percentage` double DEFAULT NULL,
  `baseline_execution_time_ms` bigint DEFAULT NULL,
  `optimized_execution_time_ms` bigint DEFAULT NULL,
  `baseline_rows` bigint DEFAULT NULL,
  `optimized_rows` bigint DEFAULT NULL,
  `total_time_ms` bigint DEFAULT NULL,
  `model_name` varchar(255) DEFAULT NULL,
  `agent_mode` varchar(64) DEFAULT NULL,
  `db_name` varchar(255) DEFAULT NULL,
  `session_id` varchar(64) DEFAULT NULL,
  `schema_version` varchar(32) DEFAULT NULL,
  `analysis_result` json NOT NULL,
  `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_created_at` (`created_at` DESC),
  KEY `idx_db_name` (`db_name`),
  KEY `idx_strategy` (`strategy`),
  KEY `idx_model_name` (`model_name`),
  KEY `idx_session_id` (`session_id`)
) ENGINE=InnoDB AUTO_INCREMENT=16 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


-- shop.`user` definition

CREATE TABLE `user` (
  `user_id` bigint NOT NULL AUTO_INCREMENT,
  `username` varchar(64) DEFAULT NULL,
  `email` varchar(128) DEFAULT NULL,
  `phone` varchar(20) DEFAULT NULL,
  `gender` tinyint DEFAULT NULL,
  `status` tinyint DEFAULT NULL,
  `register_time` datetime DEFAULT NULL,
  `last_login_time` datetime DEFAULT NULL,
  `city` varchar(64) DEFAULT NULL,
  `level` int DEFAULT NULL,
  PRIMARY KEY (`user_id`)
) ENGINE=InnoDB AUTO_INCREMENT=5373871 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


-- shop.user_behavior definition

CREATE TABLE `user_behavior` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint DEFAULT NULL,
  `product_id` bigint DEFAULT NULL,
  `behavior_type` tinyint DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=10000001 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
