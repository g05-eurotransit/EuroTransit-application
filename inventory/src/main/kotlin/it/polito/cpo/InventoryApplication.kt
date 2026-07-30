package it.polito.cpo

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.context.annotation.Bean
import java.time.Clock

@SpringBootApplication
@EnableScheduling
class InventoryApplication {
    @Bean
    fun clock(): Clock = Clock.systemUTC()
}

fun main(args: Array<String>) {
    runApplication<InventoryApplication>(*args)
}
