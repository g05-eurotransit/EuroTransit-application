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

# Registering the Debezium connectors is part of bringing the stack up, not a step to
# remember: without them the outbox row is written and never relayed, so a checkout stops
# in ACCEPTED and the UI polls forever. The script is idempotent, so re-running this is safe.
compose-up:
    docker compose up -d
    ./scripts/init-local-debezium.sh

compose-down:
    docker compose down

customer-token:
    @./scripts/local-customer-token.sh

# Environment, finite fixture manifest and credentials must be supplied by the caller.
load-checkout:
    python3 load-tests/run.py

load-checkout-verify ledger evidence output:
    python3 load-tests/verify.py --ledger '{{ledger}}' --evidence '{{evidence}}' --output '{{output}}'

load-checkout-unit:
    node --test load-tests/tests/*.test.mjs
    python3 -m unittest discover -s load-tests/tests -p 'test_*.py'
