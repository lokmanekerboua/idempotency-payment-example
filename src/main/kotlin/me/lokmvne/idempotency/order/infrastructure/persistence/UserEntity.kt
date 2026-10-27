package me.lokmvne.idempotency.order.infrastructure.persistence

import jakarta.persistence.*
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.Instant
import java.util.UUID

enum class UserRole {
    CUSTOMER,
    ADMIN
}

@Entity
@Table(name = "users")
@EntityListeners(AuditingEntityListener::class)
data class UserEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    @Column(nullable = false, unique = true, length = 255)
    val email: String,

    @Column(nullable = false, length = 255)
    val passwordHash: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val role: UserRole = UserRole.CUSTOMER,

    @CreatedDate
    @Column(nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),

    @LastModifiedDate
    @Column(nullable = false)
    val updatedAt: Instant = Instant.now()
)
