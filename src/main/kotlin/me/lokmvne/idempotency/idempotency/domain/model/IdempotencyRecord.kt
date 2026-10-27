package me.lokmvne.idempotency.idempotency.domain.model

import me.lokmvne.idempotency.idempotency.domain.enum.OperationType
import me.lokmvne.idempotency.idempotency.domain.enum.ProcessingStatus
import java.time.Instant
import java.util.UUID

data class IdempotencyRecord(
    val id: UUID,
    val userId: UUID,
    val operationType: OperationType,
    val idempotencyKey: UUID,
    val requestHash: String,
    val processingStatus: ProcessingStatus,
    val responseHttpStatus: Int?,
    val responseBody: String?,
    val resourceId: UUID?,
    val leaseOwner: String?,
    val leaseExpiresAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val expiresAt: Instant
) {
    fun isInProgress(): Boolean = processingStatus == ProcessingStatus.IN_PROGRESS

    fun isCompleted(): Boolean = processingStatus == ProcessingStatus.COMPLETED

    fun isFailed(): Boolean = processingStatus == ProcessingStatus.FAILED

    fun isLeaseValid(): Boolean {
        return leaseOwner != null && leaseExpiresAt != null && Instant.now().isBefore(leaseExpiresAt)
    }

    fun canBeReclaimedBy(workerId: String): Boolean {
        return leaseOwner == workerId || !isLeaseValid()
    }

    fun markAsCompleted(
        httpStatus: Int,
        responseBody: String,
        resourceId: UUID
    ): IdempotencyRecord {
        require(processingStatus == ProcessingStatus.IN_PROGRESS) {
            "Cannot mark as completed when status is $processingStatus"
        }
        return copy(
            processingStatus = ProcessingStatus.COMPLETED,
            responseHttpStatus = httpStatus,
            responseBody = responseBody,
            resourceId = resourceId,
            leaseOwner = null,
            leaseExpiresAt = null,
            updatedAt = Instant.now()
        )
    }

    fun markAsFailed(): IdempotencyRecord {
        require(processingStatus == ProcessingStatus.IN_PROGRESS) {
            "Cannot mark as failed when status is $processingStatus"
        }
        return copy(
            processingStatus = ProcessingStatus.FAILED,
            leaseOwner = null,
            leaseExpiresAt = null,
            updatedAt = Instant.now()
        )
    }

    fun acquireLease(workerId: String, expiresAt: Instant): IdempotencyRecord {
        require(!isCompleted()) { "Cannot acquire lease for completed operation" }
        return copy(
            leaseOwner = workerId,
            leaseExpiresAt = expiresAt,
            updatedAt = Instant.now()
        )
    }

    fun releaseLease(): IdempotencyRecord {
        return copy(
            leaseOwner = null,
            leaseExpiresAt = null,
            updatedAt = Instant.now()
        )
    }
}
