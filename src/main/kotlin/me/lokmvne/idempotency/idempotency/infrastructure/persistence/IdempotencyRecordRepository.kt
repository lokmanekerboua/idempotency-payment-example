package me.lokmvne.idempotency.idempotency.infrastructure.persistence

import me.lokmvne.idempotency.idempotency.domain.enum.OperationType
import me.lokmvne.idempotency.idempotency.domain.enum.ProcessingStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID
import jakarta.persistence.LockModeType

interface IdempotencyRecordRepository : JpaRepository<IdempotencyRecordEntity, UUID> {
    fun findByUserIdAndOperationTypeAndIdempotencyKey(
        userId: UUID,
        operationType: OperationType,
        idempotencyKey: UUID
    ): IdempotencyRecordEntity?

    @Query(
        """
        SELECT i FROM IdempotencyRecordEntity i
        WHERE i.userId = :userId
        AND i.operationType = :operationType
        AND i.idempotencyKey = :idempotencyKey
        AND i.expiresAt > :now
        """
    )
    fun findValidRecord(
        @Param("userId") userId: UUID,
        @Param("operationType") operationType: OperationType,
        @Param("idempotencyKey") idempotencyKey: UUID,
        @Param("now") now: Instant
    ): IdempotencyRecordEntity?

    @Query(
        """
        SELECT i FROM IdempotencyRecordEntity i
        WHERE i.processingStatus = 'IN_PROGRESS'
        AND (i.leaseExpiresAt IS NULL OR i.leaseExpiresAt < :now)
        AND i.createdAt < :threshold
        ORDER BY i.createdAt ASC
        LIMIT :limit
        """
    )
    fun findStaleInProgressRecords(
        @Param("now") now: Instant,
        @Param("threshold") threshold: Instant,
        @Param("limit") limit: Int
    ): List<IdempotencyRecordEntity>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        """
        SELECT i FROM IdempotencyRecordEntity i
        WHERE i.id = :id
        """
    )
    fun findByIdWithLock(@Param("id") id: UUID): IdempotencyRecordEntity?

    @Modifying
    @Query(
        """
        UPDATE IdempotencyRecordEntity i
        SET i.leaseOwner = :workerId,
            i.leaseExpiresAt = :expiresAt,
            i.updatedAt = :now
        WHERE i.id = :id
        AND (i.leaseOwner IS NULL OR i.leaseOwner = :workerId OR i.leaseExpiresAt < :now)
        """
    )
    fun claimLease(
        @Param("id") id: UUID,
        @Param("workerId") workerId: String,
        @Param("expiresAt") expiresAt: Instant,
        @Param("now") now: Instant
    ): Int

    @Modifying
    @Query(
        """
        DELETE FROM IdempotencyRecordEntity i
        WHERE i.expiresAt < :now
        AND i.processingStatus = 'COMPLETED'
        """
    )
    fun deleteExpiredCompletedRecords(@Param("now") now: Instant): Int
}
