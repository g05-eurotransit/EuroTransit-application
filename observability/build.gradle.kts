import org.springframework.boot.gradle.tasks.bundling.BootJar

plugins {
    id("services-conventions")
}

dependencies {
    implementation("org.springframework.boot:spring-boot-webclient")
}

tasks.named<BootJar>("bootJar") {
    enabled = false
}

tasks.named<Jar>("jar") {
    enabled = true
    archiveClassifier.set("")
}
