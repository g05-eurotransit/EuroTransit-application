# Chaos Experiments: Payments Latency Injection

We need to prove that the system can handle payment provider slowdowns.

## Hypothesis
If the external payment provider (or our internal sandbox adapter) experiences high latency, the `payments` service will take longer to respond. However, the `orders` service will use its Resilience4j `TimeLimiter` and `CircuitBreaker` to gracefully fail the checkout attempt without blocking downstream resources indefinitely or crashing.

## Experiment Steps (Using Chaos Mesh or similar)
1. Inject a 5-second network delay into the `payments` pods using a NetworkChaos resource.
2. Send checkout requests from the frontend or API client.
3. Observe the `orders` service logs.
4. The `orders` service should timeout the call to `payments` after 3 seconds (as configured in `application.yaml` under `resilience4j.timelimiter`).
5. After 50% of calls fail (if we send multiple requests), the CircuitBreaker will transition to `OPEN`, failing subsequent requests instantly.
6. Remove the chaos injection.
7. After the wait duration (10s), the CircuitBreaker will transition to `HALF_OPEN`.
8. Successful payment calls will close the circuit breaker.

## Chaos Mesh Manifest Example
```yaml
apiVersion: chaos-mesh.org/v1alpha1
kind: NetworkChaos
metadata:
  name: payment-latency
  namespace: default
spec:
  action: delay
  mode: all
  selector:
    namespaces:
      - default
    labelSelectors:
      app: payments
  delay:
    latency: "5s"
    correlation: "100"
    jitter: "0ms"
  duration: "1m"
```
