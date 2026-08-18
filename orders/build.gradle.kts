plugins {
    id("services-conventions")
}

dependencies {
    implementation("io.github.resilience4j:resilience4j-spring-boot3:2.2.0")
    // Reactor operators (CircuitBreaker/Retry/Bulkhead) applied to the WebClient reactive chain.
    implementation("io.github.resilience4j:resilience4j-reactor:2.2.0")
    implementation("org.springframework.boot:spring-boot-starter-data-r2dbc")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    // Client-credentials: orders obtains a ROLE_service token to authenticate its outbound
    // calls to inventory/payments (service-to-service).
    implementation("org.springframework.boot:spring-boot-starter-oauth2-client")

    // Shared wire contracts (reservation/payment DTOs + Kafka event envelope)
    implementation(project(":money-path-contracts"))

    // Correlation-id propagation (inbound WebFilter + outbound WebClient customizer),
    // shared error model, and MDC bridging for end-to-end tracing.
    implementation(project(":observability"))
    // WebClientCustomizer type lives here; needed on the compile classpath to apply the customizer.
    implementation("org.springframework.boot:spring-boot-webclient")

    // Schema migrations (Flyway runs at startup, before WebFlux; JDBC driver used only for this).
    // spring-boot-flyway carries FlywayAutoConfiguration (a separate module in Spring Boot 4).
    implementation("org.springframework.boot:spring-boot-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    // implementation (not runtimeOnly): the transactional-outbox row stores its payload as
    // io.r2dbc.postgresql.codec.Json, referenced in OutboxEvent/CheckoutOrchestrator at compile time.
    implementation("org.postgresql:r2dbc-postgresql")

    // Integration tests: real Postgres (R2DBC + Flyway) via Testcontainers + embedded Kafka.
    testImplementation("org.testcontainers:junit-jupiter:1.20.4")
    testImplementation("org.testcontainers:postgresql:1.20.4")
}
