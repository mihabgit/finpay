
CREATE TABLE idempotency_records (
                                     id UUID PRIMARY KEY,
                                     user_id UUID NOT NULL,
                                     idempotency_key VARCHAR(255) NOT NULL,
                                     request_hash CHAR(64) NOT NULL,
                                     transaction_id UUID,
                                     created_at TIMESTAMP WITH TIME ZONE NOT NULL,

                                     CONSTRAINT fk_idempotency_records_user
                                         FOREIGN KEY (user_id) REFERENCES users(id),

                                     CONSTRAINT fk_idempotency_records_transaction
                                         FOREIGN KEY (transaction_id)
                                             REFERENCES ledger_transactions(id),

                                     CONSTRAINT uk_idempotency_user_key
                                         UNIQUE (user_id, idempotency_key),

                                     CONSTRAINT uk_idempotency_transaction
                                         UNIQUE (transaction_id)
);

CREATE INDEX idx_idempotency_records_created_at
    ON idempotency_records(created_at);