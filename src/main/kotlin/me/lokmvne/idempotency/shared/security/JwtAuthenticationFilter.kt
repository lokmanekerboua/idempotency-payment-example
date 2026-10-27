package me.lokmvne.idempotency.shared.security

import io.jsonwebtoken.Claims
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource
import org.springframework.stereotype.Component
import org.slf4j.LoggerFactory
import org.springframework.web.filter.OncePerRequestFilter
import java.nio.charset.StandardCharsets
import java.util.*

@Component
class JwtAuthenticationFilter(
    private val jwtProperties: me.lokmvne.idempotency.shared.config.PaymentProperties
) : OncePerRequestFilter() {
    private val logger = LoggerFactory.getLogger(JwtAuthenticationFilter::class.java)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val authHeader = request.getHeader("Authorization")

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response)
            return
        }

        val token = authHeader.substring(7)

        try {
            val claims = parseToken(token)
            val userId = claims.subject

            if (userId != null && SecurityContextHolder.getContext().authentication == null) {
                val authorities = claims["roles"] as? List<*>
                    ?.map { SimpleGrantedAuthority(it.toString()) }
                    ?: emptyList()

                val authToken = UsernamePasswordAuthenticationToken(
                    userId,
                    null,
                    authorities
                )
                authToken.details = WebAuthenticationDetailsSource().buildDetails(request)
                SecurityContextHolder.getContext().authentication = authToken
            }
        } catch (e: Exception) {
            logger.error("JWT authentication failed", e)
        }

        filterChain.doFilter(request, response)
    }

    private fun parseToken(token: String): Claims {
        val key = Keys.hmacShaKeyFor(jwtProperties.jwt.secret.toByteArray(StandardCharsets.UTF_8))
        return Jwts.parserBuilder()
            .setSigningKey(key)
            .build()
            .parseClaimsJws(token)
            .body
    }
}
