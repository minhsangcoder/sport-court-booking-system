-- Zero-balance initialization only. Beneficiary is a logical reference to protected Identity data.
CREATE TABLE owner_wallets (
 owner_id UUID PRIMARY KEY, application_id UUID NOT NULL UNIQUE,
 beneficiary_reference UUID NOT NULL, commission_percent NUMERIC(5,2) NOT NULL CHECK(commission_percent BETWEEN 0 AND 100),
 available_balance NUMERIC(14,0) NOT NULL DEFAULT 0 CHECK(available_balance>=0),
 currency CHAR(3) NOT NULL DEFAULT 'VND', created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
