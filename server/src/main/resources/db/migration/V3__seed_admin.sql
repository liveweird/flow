-- TEMPLATE PLACEHOLDER — the bootstrap admin for a fresh install, password "changeme".
-- Outside development mode the app REFUSES to start while any active account still carries
-- this well-known hash (see infra/db/Bootstrap.kt); set ADMIN_INITIAL_PASSWORD to rotate it
-- automatically at first boot, then change it via the UI.
INSERT INTO users ("name", email, password_hash, "role")
VALUES (
    'Admin',
    'admin@flow.local',
    '$2y$12$VD60LjzPo00G5MtaWE3h9OrqYUid.MVxc5D7oHsM8oErnD9wuIvya',
    'ADMIN'
)
ON CONFLICT DO NOTHING;
