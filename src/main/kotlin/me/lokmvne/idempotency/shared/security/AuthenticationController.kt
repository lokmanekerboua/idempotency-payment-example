package me.lokmvne.idempotency.shared.security

import io.swagger.v3.oas.annotations.Operation
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/auth")
class AuthenticationController(
    private val authenticationService: AuthenticationService
) {

    @PostMapping("/register")
    @Operation(summary = "Register a new user", description = "Creates a new user account and returns a JWT token")
    fun register(@Valid @RequestBody request: AuthenticationService.RegisterRequest): ResponseEntity<AuthenticationService.AuthResponse> {
        val response = authenticationService.register(request)
        return ResponseEntity.status(HttpStatus.CREATED).body(response)
    }

    @PostMapping("/login")
    @Operation(summary = "Login", description = "Authenticates a user and returns a JWT token")
    fun login(@Valid @RequestBody request: AuthenticationService.LoginRequest): ResponseEntity<AuthenticationService.AuthResponse> {
        val response = authenticationService.login(request)
        return ResponseEntity.ok(response)
    }
}
