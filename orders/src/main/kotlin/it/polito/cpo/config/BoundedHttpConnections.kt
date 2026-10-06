package it.polito.cpo.config

import io.netty.channel.ChannelOption
import org.springframework.boot.webclient.WebClientCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import reactor.netty.http.client.HttpClient
import reactor.netty.resources.ConnectionProvider
import java.time.Duration

/** Reactor Netty pools are per remote address: a slow Payments pool cannot consume Inventory's. */
@Configuration
class BoundedHttpConnections {
    @Bean(destroyMethod = "dispose")
    fun ordersConnectionProvider(): ConnectionProvider = ConnectionProvider.builder("orders-bounded")
        .maxConnections(20)
        .pendingAcquireMaxCount(20)
        .pendingAcquireTimeout(Duration.ofSeconds(1))
        .build()

    @Bean
    fun boundedHttpCustomizer(ordersConnectionProvider: ConnectionProvider): WebClientCustomizer = WebClientCustomizer { builder ->
        builder.clientConnector(ReactorClientHttpConnector(HttpClient.create(ordersConnectionProvider)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 1000)
            .responseTimeout(Duration.ofSeconds(2))))
    }
}
