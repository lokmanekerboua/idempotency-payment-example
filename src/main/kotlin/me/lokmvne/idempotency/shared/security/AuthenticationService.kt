package me.lokmvne.idempotency.shared.security

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import me.lokmvne.idempotency.order.infrastructure.persistence.UserEntity
import me.lokmvne.idempotency.order.infrastructure.persistence.UserRepository
import me.lokmvne.idempotency.order.infrastructure.persistence.UserRole
import me.lokmvne.idempotency.shared.config.PaymentProperties
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.util.*

@Service
class AuthenticationService(
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val jwtProperties: PaymentProperties
) {

    data class RegisterRequest(
        val email: String,
        val password: String
    )

    data class LoginRequest(
        val email: String,
        val password: String
    )

    data class AuthResponse(
        val token: String,
        val userId: UUID,
        val email: String,
        val role: UserRole
    )

    fun register(request: RegisterRequest): AuthResponse {
        if (userRepository.findByEmail(request.email) != null) {
            throw IllegalArgumentException("User already exists with email: ${request.email}")
        }

        val passwordHash = passwordEncoder.encode(request.password)

        val user = UserEntity(
            email = request.email,
            passwordHash = passwordHash,
            role = UserRole.CUSTOMER
        )

        val savedUser = userRepository.save(user)

        val token = generateToken(savedUser)

        return AuthResponse(
            token = token,
            userId = requireNotNull(savedUser.id),
            email = savedUser.email,
            role = savedUser.role
        )
    }

    fun login(request: LoginRequest): AuthResponse {
        val user = userRepository.findByEmail(request.email)
            ?: throw IllegalArgumentException("Invalid credentials")

        if (!passwordEncoder.matches(request.password, user.passwordHash)) {
            throw IllegalArgumentException("Invalid credentials")
        }

        val token = generateToken(user)

        return AuthResponse(
            token = token,
            userId = requireNotNull(user.id),
            email = user.email,
            role = user.role
        )
    }

    private fun generateToken(user: UserEntity): String {
        val key = Keys.hmacShaKeyFor(jwtProperties.jwt.secret.toByteArray(StandardCharsets.UTF_8))
        val now = Date()
        val expiryDate = Date(now.time + jwtProperties.jwt.expiration.toMillis())

        return Jwts.builder()
            .setSubject(requireNotNull(user.id).toString())
            .claim("email", user.email)
            .claim("roles", listOf(user.role.name))
            .setIssuedAt(now)
            .setExpiration(expiryDate)
            .signWith(key)
            .compact()
    }
}
