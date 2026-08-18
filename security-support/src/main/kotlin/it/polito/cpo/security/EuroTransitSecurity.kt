package it.polito.cpo.security

import org.springframework.core.convert.converter.Converter
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository
import reactor.core.publisher.Mono

object EuroTransitAuthorities {
    const val CUSTOMER = "ROLE_customer"
    const val OPERATIONS = "ROLE_operations"
    const val SERVICE = "ROLE_service"
}

object EuroTransitPaths {
    val UNAUTHENTICATED_ACTUATOR_ENDPOINTS = arrayOf(
        "/actuator/health",
        "/actuator/health/**",
        "/actuator/prometheus",
    )
}

fun ServerHttpSecurity.applyStatelessApiDefaults(): ServerHttpSecurity =
    csrf { it.disable() }
        .httpBasic { it.disable() }
        .formLogin { it.disable() }
        .logout { it.disable() }
        .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())

fun keycloakRealmRoleJwtAuthenticationConverter(): Converter<Jwt, Mono<AbstractAuthenticationToken>> {
    val delegate = JwtAuthenticationConverter()
    val scopeConverter = JwtGrantedAuthoritiesConverter()

    delegate.setJwtGrantedAuthoritiesConverter { jwt ->
        val authorities = LinkedHashSet<GrantedAuthority>()
        authorities.addAll(scopeConverter.convert(jwt) ?: emptyList())
        authorities.addAll(jwt.realmRoles().map { SimpleGrantedAuthority("ROLE_$it") })
        authorities
    }

    return ReactiveJwtAuthenticationConverterAdapter(delegate)
}

private fun Jwt.realmRoles(): List<String> {
    val realmAccess = getClaim<Map<String, Any>>("realm_access") ?: return emptyList()
    val roles = realmAccess["roles"] as? Collection<*> ?: return emptyList()
    return roles.filterIsInstance<String>()
}
