package it.polito.cpo

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class OrdersApplication

fun main(args: Array<String>) {
    runApplication<OrdersApplication>(*args)
}
