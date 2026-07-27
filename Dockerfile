FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /workspace
ENV GRADLE_USER_HOME=/gradle-cache

COPY gradle gradle
COPY gradlew .
COPY build.gradle.kts settings.gradle.kts ./
COPY buildSrc buildSrc

# Gradle 9 requires every included project directory to exist, even though only the target
# service and its shared project dependencies are configured with build scripts at this point.
RUN mkdir -p catalog observability security-support money-path-contracts \
    inventory notifications orders payments

COPY observability/build.gradle.kts observability/build.gradle.kts
COPY security-support/build.gradle.kts security-support/build.gradle.kts
COPY money-path-contracts/build.gradle.kts money-path-contracts/build.gradle.kts

# Cache the wrapper, plugins, and compiled buildSrc logic independently of the target service.
RUN ./gradlew --no-daemon help

ARG SERVICE
COPY ${SERVICE}/build.gradle.kts ${SERVICE}/build.gradle.kts

# Resolve the target service's production artifacts before source changes can invalidate the layer.
RUN ./gradlew --no-daemon ":${SERVICE}:resolveProductionDependencies"

COPY observability/src observability/src
COPY security-support/src security-support/src
COPY money-path-contracts/src money-path-contracts/src
COPY ${SERVICE}/src ${SERVICE}/src

RUN ./gradlew --no-daemon ":${SERVICE}:bootJar"

RUN cp "${SERVICE}/build/libs/${SERVICE}.jar" application.jar
RUN java -Djarmode=tools -jar application.jar extract --layers --destination extracted

FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

RUN addgroup -S appgroup && adduser -S appuser -G appgroup

COPY --from=builder /workspace/extracted/dependencies/ ./
COPY --from=builder /workspace/extracted/spring-boot-loader/ ./
COPY --from=builder /workspace/extracted/snapshot-dependencies/ ./
COPY --from=builder /workspace/extracted/application/ ./

USER appuser

ENV SERVER_PORT=8080

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "application.jar"]
