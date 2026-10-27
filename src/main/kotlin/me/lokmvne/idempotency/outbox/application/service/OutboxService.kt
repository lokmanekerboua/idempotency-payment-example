package me.lokmvne.idempotency.outbox.application.service

import com.fasterxml.jackson.databind.ObjectMapper
import me.lokmvne.idempotency.outbox.domain.OutboxEvent
import me.lokmvne.idempotency.outbox.domain.OutboxStatus
import me.lokmvne.idempotency.outbox.infrastructure.OutboxEventEntity
import me.lokmvne.idempotency.outbox.infrastructure.OutboxEventRepository
import me.lokmvne.idempotency.shared.config.PaymentProperties
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
class OutboxService(
    private val outboxEventRepository: OutboxEventRepository,
    private val objectMapper: ObjectMapper,
    private val paymentProperties: PaymentProperties
) {
    private val logger = LoggerFactory.getLogger(OutboxService::class.java)

    @Transactional(propagation = Propagation.MANDATORY)
    fun publishEvent(
        aggregateId: UUID,
        aggregateType: String,
        eventType: String,
        payload: Any
    ) {
        val payloadJson = objectMapper.writeValueAsString(payload)

        val event = OutboxEventEntity(
            aggregateId = aggregateId,
            aggregateType = aggregateType,
            eventType = eventType,
            payload = payloadJson,
            status = OutboxStatus.PENDING
        )

        outboxEventRepository.save(event)
        logger.debug("Published outbox event: $eventType for aggregate $aggregateType:$aggregateId")
    }

    @Transactional
    @Scheduled(
        fixedDelayString = "\${app.payments.outbox.interval}",
        initialDelay = 30000
    )
    fun processPendingEvents() {
        if (!paymentProperties.outbox.enabled) {
            return
        }

        try {
            val pendingEvents = outboxEventRepository.findPendingEventsForProcessing(
                limit = paymentProperties.outbox.batchSize
            )

            if (pendingEvents.isEmpty()) {
                logger.debug("No pending outbox events to process")
                return
            }

            logger.info("Processing ${pendingEvents.size} pending outbox events")

            pendingEvents.forEach { event ->
                processEvent(event)
            }

        } catch (e: Exception) {
            logger.error("Error processing outbox events", e)
        }
    }

    @Transactional
    fun processEvent(event: OutboxEventEntity) {
        try {
            if (event.attemptCount >= paymentProperties.outbox.maxRetryAttempts) {
                logger.warn("Event ${event.id} exceeded max retry attempts, marking as failed")
                outboxEventRepository.updateProcessingStatus(
                    id = event.id,
                    status = OutboxStatus.FAILED,
                    processedAt = Instant.now()
                )
                return
            }

            val outboxEvent = toDomain(event)

            // In a real system, this would publish to a message broker (Kafka, RabbitMQ, etc.)
            // For this implementation, we'll just log the event
            logger.info("Publishing event: ${outboxEvent.eventType} for ${outboxEvent.aggregateType}:${outboxEvent.aggregateId}")

            outboxEventRepository.updateProcessingStatus(
                id = event.id,
                status = OutboxStatus.PROCESSED,
                processedAt = Instant.now()
            )

            logger.debug("Successfully processed outbox event: ${event.id}")

        } catch (e: Exception) {
            logger.error("Failed to process outbox event ${event.id}", e)

            if (event.attemptCount >= paymentProperties.outbox.maxRetryAttempts) {
                outboxEventRepository.updateProcessingStatus(
                    id = event.id,
                    status = OutboxStatus.FAILED,
                    processedAt = Instant.now()
                )
            }
        }
    }

    private fun toDomain(entity: OutboxEventEntity): OutboxEvent {
        return OutboxEvent(
            id = entity.id,
            aggregateId = entity.aggregateId,
            aggregateType = entity.aggregateType,
            eventType = entity.eventType,
            payload = entity.payload,
            status = entity.status,
            createdAt = entity.createdAt,
            processedAt = entity.processedAt,
            attemptCount = entity.attemptCount
        )
    }
}
