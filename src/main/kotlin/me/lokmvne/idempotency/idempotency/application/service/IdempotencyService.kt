package me.lokmvne.idempotency.idempotency.application.service

import me.lokmvne.idempotency.idempotency.domain.enum.OperationType
import me.lokmvne.idempotency.idempotency.domain.enum.ProcessingStatus
import me.lokmvne.idempotency.idempotency.domain.exception.IdempotencyKeyConflictException
import me.lokmvne.idempotency.idempotency.domain.model.IdempotencyRecord
import me.lokmvne.idempotency.idempotency.infrastructure.persistence.IdempotencyRecordEntity
import me.lokmvne.idempotency.idempotency.infrastructure.persistence.IdempotencyRecordRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

@Service
class IdempotencyService(
    private val idempotencyRecordRepository: IdempotencyRecordRepository,
    private val paymentProperties: me.lokmvne.idempotency.shared.config.PaymentProperties
) {

    data class ReservationResult(
        val record: IdempotencyRecord,
        val isNew: Boolean
    )

    data class CachedResponse(
        val httpStatus: Int,
        val body: String,
        val resourceId: UUID
    )

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun reserveIdempotencyKey(
        userId: UUID,
        operationType: OperationType,
        idempotencyKey: UUID,
        requestParameters: Map<String, Any>
    ): ReservationResult {
        val requestHash = computeRequestHash(userId, operationType, requestParameters)
        val now = Instant.now()
        val expiresAt = now.plus(paymentProperties.idempotency.keyTtl)

        // The unique constraint and atomic insert arbitrate concurrent requests across instances.
        val inserted = idempotencyRecordRepository.insertIfAbsent(
            UUID.randomUUID(), userId, operationType.name, idempotencyKey, requestHash, now, expiresAt
        )
        val record = idempotencyRecordRepository.findByUserIdAndOperationTypeAndIdempotencyKey(
            userId, operationType, idempotencyKey
        ) ?: throw IllegalStateException("Idempotency record unavailable after reservation")
        if (record.requestHash != requestHash) {
            throw IdempotencyKeyConflictException(userId, operationType.name, idempotencyKey)
        }
        return ReservationResult(toDomain(record), inserted == 1)
    }

    @Transactional
    fun markAsCompleted(
        recordId: UUID,
        httpStatus: Int,
        responseBody: String,
        resourceId: UUID
    ) {
        val record = idempotencyRecordRepository.findById(recordId)
            .orElseThrow { IllegalArgumentException("Idempotency record not found: $recordId") }

        record.processingStatus = ProcessingStatus.COMPLETED
        record.responseHttpStatus = httpStatus
        record.responseBody = responseBody
        record.resourceId = resourceId
        record.leaseOwner = null
        record.leaseExpiresAt = null

        idempotencyRecordRepository.save(record)
    }

    @Transactional
    fun markAsFailed(recordId: UUID) {
        val record = idempotencyRecordRepository.findById(recordId)
            .orElseThrow { IllegalArgumentException("Idempotency record not found: $recordId") }

        record.processingStatus = ProcessingStatus.FAILED
        record.leaseOwner = null
        record.leaseExpiresAt = null

        idempotencyRecordRepository.save(record)
    }

    @Transactional(readOnly = true)
    fun getCachedResponse(
        userId: UUID,
        operationType: OperationType,
        idempotencyKey: UUID
    ): CachedResponse? {
        val now = Instant.now()
        val record = idempotencyRecordRepository.findValidRecord(
            userId = userId,
            operationType = operationType,
            idempotencyKey = idempotencyKey,
            now = now
        )

        if (record?.processingStatus == ProcessingStatus.COMPLETED) {
            return CachedResponse(
                httpStatus = record.responseHttpStatus ?: return null,
                body = record.responseBody ?: return null,
                resourceId = record.resourceId ?: return null
            )
        }

        return null
    }

    @Transactional
    fun cleanupExpiredRecords(): Int {
        val now = Instant.now()
        return idempotencyRecordRepository.deleteExpiredCompletedRecords(now)
    }

    @Transactional
    fun claimLease(recordId: UUID, workerId: String): Boolean {
        val now = Instant.now()
        val expiresAt = now.plus(paymentProperties.idempotency.processingTimeout)

        val updated = idempotencyRecordRepository.claimLease(
            id = recordId,
            workerId = workerId,
            expiresAt = expiresAt,
            now = now
        )

        return updated > 0
    }

    @Transactional
    fun releaseLease(recordId: UUID) {
        val record = idempotencyRecordRepository.findById(recordId)
            .orElseThrow { IllegalArgumentException("Idempotency record not found: $recordId") }

        record.leaseOwner = null
        record.leaseExpiresAt = null

        idempotencyRecordRepository.save(record)
    }

    private fun computeRequestHash(
        userId: UUID,
        operationType: OperationType,
        parameters: Map<String, Any>
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")

        digest.update(userId.toString().toByteArray())
        digest.update(operationType.name.toByteArray())

        parameters.toSortedMap().forEach { (key, value) ->
            val keyBytes = key.toByteArray(Charsets.UTF_8)
            val valueBytes = value.toString().toByteArray(Charsets.UTF_8)
            digest.update(java.nio.ByteBuffer.allocate(4).putInt(keyBytes.size).array())
            digest.update(keyBytes)
            digest.update(java.nio.ByteBuffer.allocate(4).putInt(valueBytes.size).array())
            digest.update(valueBytes)
        }

        val hashBytes = digest.digest()
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    private fun toDomain(entity: IdempotencyRecordEntity): IdempotencyRecord {
        return IdempotencyRecord(
            id = entity.id,
            userId = entity.userId,
            operationType = entity.operationType,
            idempotencyKey = entity.idempotencyKey,
            requestHash = entity.requestHash,
            processingStatus = entity.processingStatus,
            responseHttpStatus = entity.responseHttpStatus,
            responseBody = entity.responseBody,
            resourceId = entity.resourceId,
            leaseOwner = entity.leaseOwner,
            leaseExpiresAt = entity.leaseExpiresAt,
            createdAt = entity.createdAt,
            updatedAt = entity.updatedAt,
            expiresAt = entity.expiresAt
        )
    }
}
