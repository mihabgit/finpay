
CREATE TABLE wallets (
                         id UUID PRIMARY KEY,
                         user_id UUID NOT NULL,
                         currency CHAR(3) NOT NULL,
                         balance NUMERIC(19, 4) NOT NULL DEFAULT 0,
                         status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
                         created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                         updated_at TIMESTAMP WITH TIME ZONE NOT NULL,

                         CONSTRAINT fk_wallets_user
                             FOREIGN KEY (user_id) REFERENCES users(id),

                         CONSTRAINT chk_wallets_balance_non_negative
                             CHECK (balance >= 0),

                         CONSTRAINT chk_wallets_status
                             CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED')),

                         CONSTRAINT uk_wallets_user_currency
                             UNIQUE (user_id, currency)
);

CREATE INDEX idx_wallets_user_id ON wallets(user_id);


CREATE TABLE ledger_transactions (
                                     id UUID PRIMARY KEY,
                                     reference VARCHAR(100) NOT NULL,
                                     transaction_type VARCHAR(30) NOT NULL,
                                     status VARCHAR(20) NOT NULL,
                                     description VARCHAR(255),
                                     created_at TIMESTAMP WITH TIME ZONE NOT NULL,

                                     CONSTRAINT uk_ledger_transactions_reference
                                         UNIQUE (reference),

                                     CONSTRAINT chk_ledger_transactions_status
                                         CHECK (status IN ('PENDING', 'POSTED', 'REVERSED'))
);

CREATE INDEX idx_ledger_transactions_created_at
    ON ledger_transactions(created_at);


CREATE TABLE ledger_entries (
                                id UUID PRIMARY KEY,
                                transaction_id UUID NOT NULL,
                                wallet_id UUID NOT NULL,
                                entry_type VARCHAR(10) NOT NULL,
                                amount NUMERIC(19, 4) NOT NULL,
                                currency CHAR(3) NOT NULL,
                                created_at TIMESTAMP WITH TIME ZONE NOT NULL,

                                CONSTRAINT fk_ledger_entries_transaction
                                    FOREIGN KEY (transaction_id)
                                        REFERENCES ledger_transactions(id),

                                CONSTRAINT fk_ledger_entries_wallet
                                    FOREIGN KEY (wallet_id) REFERENCES wallets(id),

                                CONSTRAINT chk_ledger_entries_type
                                    CHECK (entry_type IN ('DEBIT', 'CREDIT')),

                                CONSTRAINT chk_ledger_entries_amount
                                    CHECK (amount > 0)
);

CREATE INDEX idx_ledger_entries_transaction_id
    ON ledger_entries(transaction_id);

CREATE INDEX idx_ledger_entries_wallet_id_created_at
    ON ledger_entries(wallet_id, created_at);