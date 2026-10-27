package me.lokmvne.idempotency.idempotency.domain.exception

import me.lokmvne.idempotency.shared.exception.DomainException
import java.util.UUID

class IdempotencyKeyConflictException(
    val userId: UUID,
    val operationType: String,
    val idempotencyKey: UUID
) : DomainException("Idempotency key conflict for user $userId, operation $operationType, key $idempotencyKey")
