-- Historical accepted terms remain immutable for existing financial obligations.
-- New direct peer purchases do not depend on an Owner cancellation policy.
ALTER TABLE peer_usage_attempts ALTER COLUMN refund_policy_hash DROP NOT NULL;
