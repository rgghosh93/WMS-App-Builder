-- People sign in to devices with a PIN and to the desktop with a password; they may have either or both.
ALTER TABLE app_users ALTER COLUMN pin_hash DROP NOT NULL;
ALTER TABLE app_users ADD COLUMN password_hash VARCHAR(200);
ALTER TABLE app_users ADD COLUMN must_change_password BOOLEAN DEFAULT FALSE NOT NULL;

ALTER TABLE device_sessions ADD COLUMN kind VARCHAR(10) DEFAULT 'DEVICE' NOT NULL;

-- A role is a set of permissions. The administrator role always has all of them.
CREATE TABLE roles (
    id             UUID          NOT NULL,
    name           VARCHAR(80)   NOT NULL,
    description    VARCHAR(250),
    administrator  BOOLEAN       DEFAULT FALSE NOT NULL,
    version        BIGINT        NOT NULL,
    CONSTRAINT pk_roles PRIMARY KEY (id),
    CONSTRAINT uq_roles_name UNIQUE (name)
);
CREATE TABLE role_permissions (
    role_id     UUID         NOT NULL,
    permission  VARCHAR(40)  NOT NULL,
    CONSTRAINT pk_role_permissions PRIMARY KEY (role_id, permission),
    CONSTRAINT fk_role_permissions_role FOREIGN KEY (role_id) REFERENCES roles (id) ON DELETE CASCADE
);
CREATE TABLE user_roles (
    user_id  UUID NOT NULL,
    role_id  UUID NOT NULL,
    CONSTRAINT pk_user_roles PRIMARY KEY (user_id, role_id),
    CONSTRAINT fk_user_roles_user FOREIGN KEY (user_id) REFERENCES app_users (id) ON DELETE CASCADE,
    CONSTRAINT fk_user_roles_role FOREIGN KEY (role_id) REFERENCES roles (id)
);
CREATE INDEX ix_user_roles_role ON user_roles (role_id);

INSERT INTO roles (id, name, description, administrator, version) VALUES
    ('00000000-0000-4000-8000-000000000001', 'Administrator', 'Everything, including users and roles', TRUE, 0),
    ('00000000-0000-4000-8000-000000000002', 'Supervisor', 'Runs the warehouse: everything but users and roles', FALSE, 0),
    ('00000000-0000-4000-8000-000000000003', 'Operator', 'Works on mobile devices and at pack stations', FALSE, 0),
    ('00000000-0000-4000-8000-000000000004', 'Viewer', 'Sees the desktop but changes nothing', FALSE, 0);
INSERT INTO role_permissions (role_id, permission) VALUES
    ('00000000-0000-4000-8000-000000000002', 'DESKTOP'),
    ('00000000-0000-4000-8000-000000000002', 'MASTER_DATA'),
    ('00000000-0000-4000-8000-000000000002', 'INBOUND'),
    ('00000000-0000-4000-8000-000000000002', 'PUTAWAY_RULES'),
    ('00000000-0000-4000-8000-000000000002', 'ORDERS'),
    ('00000000-0000-4000-8000-000000000002', 'SHIPPING'),
    ('00000000-0000-4000-8000-000000000002', 'DEVICE_RECEIVE'),
    ('00000000-0000-4000-8000-000000000002', 'DEVICE_PUTAWAY'),
    ('00000000-0000-4000-8000-000000000002', 'DEVICE_PICK'),
    ('00000000-0000-4000-8000-000000000002', 'DEVICE_PACK'),
    ('00000000-0000-4000-8000-000000000002', 'DEVICE_LOAD'),
    ('00000000-0000-4000-8000-000000000003', 'DEVICE_RECEIVE'),
    ('00000000-0000-4000-8000-000000000003', 'DEVICE_PUTAWAY'),
    ('00000000-0000-4000-8000-000000000003', 'DEVICE_PICK'),
    ('00000000-0000-4000-8000-000000000003', 'DEVICE_PACK'),
    ('00000000-0000-4000-8000-000000000003', 'DEVICE_LOAD'),
    ('00000000-0000-4000-8000-000000000004', 'DESKTOP');

-- Everyone so far works on devices.
INSERT INTO user_roles (user_id, role_id)
SELECT id, CAST('00000000-0000-4000-8000-000000000003' AS UUID) FROM app_users;
