-- Minimal ScheduleMe-shaped schema for CI: just what the live tests touch.
CREATE TYPE event_status AS ENUM ('SCHEDULED', 'DONE', 'CANCELLED');
CREATE TYPE task_priority AS ENUM ('LOW', 'MEDIUM', 'HIGH');

CREATE TABLE users (
    id      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    email   text NOT NULL UNIQUE,
    enabled boolean NOT NULL DEFAULT true
);

CREATE TABLE events (
    id         bigserial PRIMARY KEY,
    user_id    uuid NOT NULL REFERENCES users,
    title      text NOT NULL,
    start_time timestamptz NOT NULL,
    status     event_status NOT NULL DEFAULT 'SCHEDULED'
);

CREATE TABLE tasks (
    id       bigserial PRIMARY KEY,
    user_id  uuid NOT NULL REFERENCES users,
    title    text NOT NULL,
    due_date date,
    priority task_priority NOT NULL DEFAULT 'MEDIUM'
);

INSERT INTO users (email) VALUES ('demo@scheduleme.local'), ('second@scheduleme.local');
INSERT INTO events (user_id, title, start_time)
    SELECT id, 'Stand-up', CURRENT_DATE + time '09:30' FROM users WHERE email = 'demo@scheduleme.local';
INSERT INTO tasks (user_id, title, due_date, priority)
    SELECT id, 'Write report', CURRENT_DATE, 'HIGH' FROM users WHERE email = 'demo@scheduleme.local';
