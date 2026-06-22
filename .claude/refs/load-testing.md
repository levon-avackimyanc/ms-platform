# Load Testing Reference

Conventions for the **load** layer of Test scope. Cross-stack (the tool is chosen
per project); routed by the `load-testing#*` tags. The `#basics` section is the
companion baseline auto-included for any load task.

<!-- section:basics -->
## Methodology & gating

- **Pick the workload model first.** Closed model (fixed VUs + think-time) vs open
  model (fixed arrival rate / RPS). Open models expose queuing better for APIs.
- **Test types:** *load* (expected peak), *stress* (push past peak to find the
  break point), *soak/endurance* (sustained run to surface leaks/degradation),
  *spike* (sudden surge + recovery).
- **Report percentiles, never averages.** Gate on **p95/p99 latency**, **error
  rate**, and **throughput (RPS)**. A mean hides the tail.
- **Thresholds are pass/fail gates** — this is what makes a load run a *test*, not
  an experiment. e.g. `p95 < 200ms AND error_rate < 1%`; breach → the run fails.
  These thresholds come from the increment's NFRs (and the test model).
- **Realism:** model ramp-up/steady/ramp-down, think-time, realistic data, and
  **correlate dynamic values** (auth tokens, created ids) across requests.
- **Isolation:** run against a dedicated/staging env, warm up first, pin resources.
  Never load-test prod without explicit sign-off.
- Keep load scripts in VCS next to the suite; parameterize target/VUs/duration via
  env so the same script runs locally and in CI.
<!-- /section:basics -->

<!-- section:k6 -->
## k6 (JavaScript — Grafana k6)

Best default for HTTP/gRPC APIs; thresholds are first-class; clean CI exit codes.

```javascript
import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  stages: [
    { duration: '30s', target: 50 },   // ramp-up
    { duration: '2m',  target: 50 },   // steady
    { duration: '30s', target: 0 },    // ramp-down
  ],
  thresholds: {                        // pass/fail gates
    http_req_duration: ['p(95)<200'],
    http_req_failed:   ['rate<0.01'],
  },
};

export default function () {
  const res = http.get(`${__ENV.BASE_URL}/r/abc123`);
  check(res, { 'status is 302': (r) => r.status === 302 });
  sleep(1);
}
```
Run: `k6 run -e BASE_URL=http://staging script.js` — a threshold breach exits non-zero (CI gate).
<!-- /section:k6 -->

<!-- section:gatling -->
## Gatling (Scala/Java DSL)

JVM-native — natural fit for Java/Spring stacks; assertions on percentiles.

```scala
class RedirectSimulation extends Simulation {
  val httpProtocol = http.baseUrl(System.getProperty("baseUrl"))
  val scn = scenario("redirect")
    .exec(http("get").get("/r/abc123").check(status.is(302)))
  setUp(scn.inject(rampUsers(50).during(30.seconds)))
    .protocols(httpProtocol)
    .assertions(
      global.responseTime.percentile(95).lt(200),
      global.failedRequests.percent.lt(1),
    )
}
```
Run via the Maven plugin: `mvn gatling:test` — failed assertions fail the build.
<!-- /section:gatling -->

<!-- section:jmeter -->
## JMeter

Author in GUI, run headless in CI; keep `.jmx` plans in VCS, parameterized with
`__P()` properties. Good for legacy/enterprise environments.

```
jmeter -n -t plan.jmx -Jusers=50 -Jduration=120 -l results.jtl -e -o report/
```
Gate on percentiles via Duration/Response Assertions in the plan, or post-process
`results.jtl` (Taurus `bzt`, or a small script asserting p95/error-rate) so a
breach returns non-zero in CI.
<!-- /section:jmeter -->

<!-- section:locust -->
## Locust (Python)

Pythonic — fits Python stacks and complex, branching user flows; distributed mode
for scale.

```python
from locust import HttpUser, task, between

class RedirectUser(HttpUser):
    wait_time = between(0.5, 2)

    @task
    def redirect(self):
        with self.client.get("/r/abc123", name="/r/[code]",
                             catch_response=True, allow_redirects=False) as r:
            if r.status_code != 302:
                r.failure(f"expected 302, got {r.status_code}")
```
Run headless with a gate: `locust -f locustfile.py --headless -u 50 -r 10 -t 2m
--host http://staging --csv run` then assert p95/failure-ratio from `run_stats.csv`.
<!-- /section:locust -->
