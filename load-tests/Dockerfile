FROM grafana/k6:0.57.0 AS k6
FROM python:3.13-alpine
COPY --from=k6 /usr/bin/k6 /usr/local/bin/k6
WORKDIR /tests
COPY load-tests/ /tests/
RUN addgroup -g 10001 runner && adduser -D -u 10001 -G runner runner && mkdir /results && chown runner:runner /results
USER 10001:10001
ENTRYPOINT ["python3", "/tests/run.py"]
CMD ["checkout.js"]
