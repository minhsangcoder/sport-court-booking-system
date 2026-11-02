-- Uploaded profile photos. One per user; the random id is the public address, so replacing a photo retires the old URL.
CREATE TABLE user_avatars (
    id         UUID PRIMARY KEY,
    user_id    UUID NOT NULL UNIQUE REFERENCES users (id) ON DELETE CASCADE,
    content    BYTEA NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
