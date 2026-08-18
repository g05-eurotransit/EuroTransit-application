package it.polito.cpo.security

import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.jwt.Jwt
import reactor.test.StepVerifier
import java.time.Instant
import kotlin.test.assertTrue

class EuroTransitSecurityTests {
    @Test
    fun `converter maps Keycloak realm roles and standard scopes`() {
        val jwt = Jwt.withTokenValue("token")
            .header("alg", "none")
            .subject("alice")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(300))
            .claim("scope", "orders:read")
            .claim("realm_access", mapOf("roles" to listOf("customer", "operations")))
            .build()

        StepVerifier.create(keycloakRealmRoleJwtAuthenticationConverter().convert(jwt))
            .assertNext { authentication ->
                val authorities = authentication.authorities.map { it.authority }.toSet()
                assertTrue(EuroTransitAuthorities.CUSTOMER in authorities)
                assertTrue(EuroTransitAuthorities.OPERATIONS in authorities)
                assertTrue("SCOPE_orders:read" in authorities)
            }
            .verifyComplete()
    }
}
