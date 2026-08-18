plugins {
    id("services-conventions")
}

dependencies {
    implementation("io.github.resilience4j:resilience4j-spring-boot3:2.2.0")
    // Reactor operators (CircuitBreaker/Retry/Bulkhead) applied to the provider WebClient chain.
    implementation("io.github.resilience4j:resilience4j-reactor:2.2.0")
    implementation("org.springframework.boot:spring-boot-starter-data-r2dbc")
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.kafka:spring-kafka")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation(project(":money-path-contracts"))
    implementation(project(":observability"))
    
    implementation("org.springframework.boot:spring-boot-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    
    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("org.postgresql:r2dbc-postgresql")

    // Integration test: real Postgres (R2DBC + Flyway) via Testcontainers + embedded Kafka.
    testImplementation("org.testcontainers:junit-jupiter:1.20.4")
    testImplementation("org.testcontainers:postgresql:1.20.4")
}
