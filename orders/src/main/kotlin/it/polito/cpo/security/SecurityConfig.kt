package it.polito.cpo.security

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.method.configuration.EnableReactiveMethodSecurity
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.web.server.SecurityWebFilterChain

@Configuration
@EnableWebFluxSecurity
@EnableReactiveMethodSecurity
class SecurityConfig {
    @Bean
    fun springSecurityFilterChain(http: ServerHttpSecurity): SecurityWebFilterChain =
        http
            .applyStatelessApiDefaults()
            .authorizeExchange {
                it.pathMatchers(*EuroTransitPaths.UNAUTHENTICATED_ACTUATOR_ENDPOINTS).permitAll()
                it.pathMatchers(HttpMethod.POST, "/api/orders").hasAuthority(EuroTransitAuthorities.CUSTOMER)
                // Without this rule anyExchange().denyAll() makes the existing cancel endpoint
                // unreachable for everyone. `*` is a single-segment PathPattern wildcard, so it cannot
                // widen beyond the cancel action itself. Ownership is enforced in the controller.
                it.pathMatchers(HttpMethod.POST, "/api/orders/*/cancel")
                    .hasAnyAuthority(EuroTransitAuthorities.CUSTOMER, EuroTransitAuthorities.OPERATIONS)
                // Same ownership rule as cancel: owner or operations. Declared before the reconciliation
                // rules only for readability — the single-segment wildcard cannot collide with the
                // two-segment `/reconciliation/**` paths below.
                it.pathMatchers(HttpMethod.POST, "/api/orders/*/refund")
                    .hasAnyAuthority(EuroTransitAuthorities.CUSTOMER, EuroTransitAuthorities.OPERATIONS)
                // Internal operator API. hasAuthority(OPERATIONS) and not hasAnyAuthority(..., SERVICE):
                // a service credential drives automated internal calls and must never be able to
                // redrive or close a customer's checkout incident. Declared before the GET rule since
                // these are POSTs and would otherwise fall through to denyAll().
                it.pathMatchers(HttpMethod.POST, "/api/orders/*/reconciliation/redrive")
                    .hasAuthority(EuroTransitAuthorities.OPERATIONS)
                it.pathMatchers(HttpMethod.POST, "/api/orders/*/reconciliation/*/resolve")
                    .hasAuthority(EuroTransitAuthorities.OPERATIONS)
                // The operator list of open incidents. Ahead of the order-read rule, which would
                // otherwise let any customer read every customer's stuck orders. The double wildcard
                // also matches the bare path, and keeps a trailing-slash variant on this rule should
                // trailing-slash matching ever be turned on.
                it.pathMatchers(HttpMethod.GET, "/api/orders/reconciliation/**")
                    .hasAuthority(EuroTransitAuthorities.OPERATIONS)
                it.pathMatchers(HttpMethod.GET, "/api/orders/**")
                    .hasAnyAuthority(EuroTransitAuthorities.CUSTOMER, EuroTransitAuthorities.OPERATIONS)
                it.anyExchange().denyAll()
            }
            .oauth2ResourceServer {
                it.jwt { jwt -> jwt.jwtAuthenticationConverter(keycloakRealmRoleJwtAuthenticationConverter()) }
            }
            .build()
}
