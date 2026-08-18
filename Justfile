set shell := ["bash", "-euo", "pipefail", "-c"]

default:
    just --list

build:
    ./gradlew --no-daemon build

ci-local:
    act pull_request -j build

smoke-kind:
    act workflow_dispatch -j kind-smoke --container-options "-v /var/run/docker.sock:/var/run/docker.sock"

pr-check: ci-local

compose-config:
    docker compose config

compose-build:
    docker compose build

compose-up:
    docker compose up -d

compose-down:
    docker compose down

customer-token:
    @./scripts/local-customer-token.sh
