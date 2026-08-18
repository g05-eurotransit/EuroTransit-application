package it.polito.cpo.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.oauth2.client.AuthorizedClientServiceReactiveOAuth2AuthorizedClientManager
import org.springframework.security.oauth2.client.InMemoryReactiveOAuth2AuthorizedClientService
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientManager
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientProviderBuilder
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository
import org.springframework.security.oauth2.client.web.reactive.function.client.ServerOAuth2AuthorizedClientExchangeFilterFunction

/**
 * Service-to-service authentication for orders' outbound calls.
 *
 * Uses the `client_credentials` grant to obtain a ROLE_service token from Keycloak and attach it as a
 * Bearer on every WebClient call to inventory/payments (both require ROLE_service on their paths).
 *
 * The manager is the SERVICE-context variant (not the request-scoped one): the checkout pipeline runs
 * in a Kafka consumer with no ServerWebExchange, so a request-bound authorized client manager would
 * have no context to resolve the token from.
 */
@Configuration
class ServiceAuthConfig {

    @Bean
    fun serviceAuthorizedClientManager(
        clientRegistrationRepository: ReactiveClientRegistrationRepository,
    ): ReactiveOAuth2AuthorizedClientManager {
        val authorizedClientService = InMemoryReactiveOAuth2AuthorizedClientService(clientRegistrationRepository)
        val manager = AuthorizedClientServiceReactiveOAuth2AuthorizedClientManager(
            clientRegistrationRepository,
            authorizedClientService,
        )
        manager.setAuthorizedClientProvider(
            ReactiveOAuth2AuthorizedClientProviderBuilder.builder().clientCredentials().build(),
        )
        return manager
    }

    @Bean
    fun serviceAuthExchangeFilter(
        manager: ReactiveOAuth2AuthorizedClientManager,
    ): ServerOAuth2AuthorizedClientExchangeFilterFunction {
        val filter = ServerOAuth2AuthorizedClientExchangeFilterFunction(manager)
        filter.setDefaultClientRegistrationId("keycloak-service")
        return filter
    }
}
