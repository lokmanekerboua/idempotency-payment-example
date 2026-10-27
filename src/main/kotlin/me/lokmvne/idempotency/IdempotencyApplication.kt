package me.lokmvne.idempotency

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class IdempotencyApplication

fun main(args: Array<String>) {
    runApplication<IdempotencyApplication>(*args)
}
