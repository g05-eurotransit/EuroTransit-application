FROM quay.io/debezium/connect:2.7.0.Final AS debezium

FROM quay.io/strimzi/kafka:0.46.1-kafka-3.9.0

LABEL org.opencontainers.image.title="EuroTransit Debezium Kafka Connect" \
      org.opencontainers.image.description="Strimzi Kafka Connect with the Debezium PostgreSQL connector" \
      org.opencontainers.image.version="strimzi-0.46.1-kafka-3.9.0-debezium-2.7.0"

USER root:root
COPY --from=debezium --chown=1001:0 \
  /kafka/connect/debezium-connector-postgres/ \
  /opt/kafka/plugins/debezium-postgres/
USER 1001
