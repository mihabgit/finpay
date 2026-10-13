
package com.finpay.idempotency.repository;

import com.finpay.idempotency.entity.IdempotencyRecord;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, UUID> {

    Optional<IdempotencyRecord> findByUserIdAndIdempotencyKey(UUID userId, String idempotencyKey);

    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT INTO idempotency_records (
                id,
                user_id,
                idempotency_key,
                request_hash,
                created_at
            )
            VALUES (
                :id,
                :userId,
                :idempotencyKey,
                :requestHash,
                :createdAt
            )
            ON CONFLICT (user_id, idempotency_key) DO NOTHING
            """, nativeQuery = true)
    int reserve(
            @Param("id") UUID id,
            @Param("userId") UUID userId,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("requestHash") String requestHash,
            @Param("createdAt") OffsetDateTime createdAt
    );
}