-- Seed product data for Inventory Service
-- This file runs via docker-entrypoint-initdb.d AFTER Flyway migrations run on service startup.
-- Note: Flyway runs when the inventory-service starts, not at DB container init time.
-- This seed file adds test products assuming Flyway has already created the products table.
-- If the table doesn't exist yet (race condition), the service re-seeds via data.sql.
-- Products are inserted here for convenience when inspecting the DB directly.

-- This is intentionally left minimal; the inventory-service/src/main/resources/data.sql
-- handles seeding within the application lifecycle to avoid race conditions.
SELECT 1; -- no-op placeholder
