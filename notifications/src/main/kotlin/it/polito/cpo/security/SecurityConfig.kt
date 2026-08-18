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
                it.pathMatchers(HttpMethod.GET, "/api/notifications/**")
                    .hasAnyAuthority(EuroTransitAuthorities.CUSTOMER, EuroTransitAuthorities.OPERATIONS)
                it.anyExchange().denyAll()
            }
            .oauth2ResourceServer {
                it.jwt { jwt -> jwt.jwtAuthenticationConverter(keycloakRealmRoleJwtAuthenticationConverter()) }
            }
            .build()
}
