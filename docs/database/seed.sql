-- Development/demo seed. Run after schema.sql on a fresh schema.
USE webox;

INSERT INTO dish
  (id, name, description, base_price, category, protein_source, spice_level, image_url, published)
VALUES
  (1, 'Kung Pao Chicken', 'Classic Sichuan dish, chicken stir-fried with peanuts and dried chili', 22.00, 'CHINESE', 'Chicken', 'MEDIUM', '/media/seed/image_01.jpg', TRUE),
  (2, 'Caesar Salad', 'Fresh romaine lettuce with Parmesan and Caesar dressing', 28.50, 'LIGHT_MEAL', 'None', 'NONE', '/media/seed/image_02.jpg', TRUE),
  (3, 'Salmon Sashimi Set', 'Fresh salmon sashimi with rice and miso soup', 45.00, 'JAPANESE', 'Fish', 'NONE', '/media/seed/image_03.jpg', TRUE),
  (4, 'Tomato Pasta', 'Classic Italian tomato pasta with fresh basil', 26.50, 'WESTERN', 'None', 'NONE', '/media/seed/image_04.jpg', TRUE),
  (5, 'Tom Yum Soup', 'Thai hot and sour shrimp soup with lemongrass, galangal, and lime leaves', 32.00, 'SOUTHEAST_ASIAN', 'Shrimp', 'HOT', '/media/seed/image_05.jpg', TRUE),
  (6, 'Chicken Quinoa Bowl', 'Low-fat high-protein grilled chicken with quinoa, avocado, and vegetables', 35.80, 'LIGHT_MEAL', 'Chicken', 'NONE', '/media/seed/image_06.jpg', TRUE),
  (7, 'Mapo Tofu', 'Sichuan classic with soft tofu and spicy minced meat', 18.00, 'CHINESE', 'Tofu, Pork', 'MEDIUM', '/media/seed/image_07.jpg', TRUE),
  (8, 'Korean Bibimbap', 'Stone-pot mixed rice with vegetables, fried egg, and chili sauce', 30.00, 'KOREAN', 'Egg', 'MILD', '/media/seed/image_08.jpg', TRUE),
  (9, 'Classic Beef Burger', 'Angus beef patty with lettuce, tomato, and onion', 38.00, 'WESTERN', 'Beef', 'NONE', '/media/seed/image_09.jpg', TRUE);

INSERT INTO dish_allergen (dish_id, allergen) VALUES
  (1, 'PEANUTS'),
  (2, 'DAIRY'), (2, 'EGG'),
  (3, 'FISH'),
  (4, 'GLUTEN'),
  (5, 'SHELLFISH'),
  (7, 'SOY'),
  (8, 'EGG'), (8, 'SOY'),
  (9, 'GLUTEN'), (9, 'DAIRY');

INSERT INTO dish_option_group
  (id, dish_id, name, selection_type, required_selection, min_selections, max_selections, display_order)
VALUES
  (1, 2, 'Add-ons', 'MULTIPLE', FALSE, 0, 3, 1),
  (2, 4, 'Pasta Type', 'SINGLE', TRUE, 1, 1, 1),
  (3, 4, 'Add-ons', 'MULTIPLE', FALSE, 0, 2, 2),
  (4, 6, 'Base', 'SINGLE', TRUE, 1, 1, 1),
  (5, 8, 'Add-ons', 'MULTIPLE', FALSE, 0, 3, 1),
  (6, 9, 'Bun', 'SINGLE', TRUE, 1, 1, 1),
  (7, 9, 'Sauce', 'SINGLE', TRUE, 1, 1, 2),
  (8, 9, 'Add-ons', 'MULTIPLE', FALSE, 0, 3, 3);

INSERT INTO dish_option_value
  (id, option_group_id, name, price_delta, display_order)
VALUES
  (1, 1, 'Grilled Chicken', 6.00, 1), (2, 1, 'Bacon', 5.00, 2), (3, 1, 'Avocado', 4.00, 3),
  (4, 2, 'Spaghetti', 0.00, 1), (5, 2, 'Fusilli', 0.00, 2), (6, 2, 'Penne', 0.00, 3),
  (7, 3, 'Bacon', 5.00, 1), (8, 3, 'Cheese', 3.00, 2),
  (9, 4, 'Quinoa', 0.00, 1), (10, 4, 'Brown Rice', 0.00, 2), (11, 4, 'Mixed Grains', 0.00, 3),
  (12, 5, 'Cheese', 3.00, 1), (13, 5, 'Fried Egg', 2.00, 2), (14, 5, 'Beef Slices', 8.00, 3),
  (15, 6, 'Plain', 0.00, 1), (16, 6, 'Whole Wheat', 0.00, 2),
  (17, 7, 'Ketchup', 0.00, 1), (18, 7, 'Mustard', 0.00, 2), (19, 7, 'Mayo', 0.00, 3), (20, 7, 'BBQ', 0.00, 4),
  (21, 8, 'Cheese', 3.00, 1), (22, 8, 'Bacon', 5.00, 2), (23, 8, 'Fried Egg', 2.00, 3);

INSERT INTO daily_menu (id, menu_date, status, publication_version, published_at) VALUES
  (1, CURRENT_DATE(), 'PUBLISHED', 1, CURRENT_TIMESTAMP(3)),
  (2, DATE_ADD(CURRENT_DATE(), INTERVAL 1 DAY), 'PUBLISHED', 1, CURRENT_TIMESTAMP(3));

INSERT INTO daily_menu_item
  (daily_menu_id, dish_id, initial_stock, remaining_stock, visible)
SELECT 1, id, 20, 20, TRUE FROM dish WHERE published = TRUE;

INSERT INTO daily_menu_item
  (daily_menu_id, dish_id, initial_stock, remaining_stock, visible)
SELECT 2, id, 20, 20, TRUE FROM dish WHERE published = TRUE;

-- Users are intentionally not inserted here with plaintext or a shared hard-coded hash.
-- The local-profile bootstrap command will BCrypt-hash passwords supplied through
-- WEBOX_DEMO_ADMIN_PASSWORD / WEBOX_DEMO_EMPLOYEE_PASSWORD and create demo accounts.

