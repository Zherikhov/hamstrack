# Observability stack — logs, metrics, dashboards, alerts

Status: **approved direction, spec for build**. Author: systems-analyst. Date: 2026-08-06.
Related: `docs/ops-prod-hardening.md`, `CLAUDE.md`, `.env.prod.example`, `docker-compose.prod.yml`, `.github/workflows/deploy.yml`.

## 1. Overview & goals
Self-hosted, zero-external-SaaS view of the running system: centralized logs (Loki), technical metrics (HTTP/JVM/HikariCP/Postgres/host/container), business metrics (registrations, active users, issues/projects/workspaces created, login success/failure, rate-limit hits, invites, email failures, attachment uploads+bytes), and a small alert set.

**Locked non-negotiables:** Grafana stack self-hosted on the same EC2 box via compose; app instrumentation compiled into BOTH modes; Actuator on a separate internal management port never routed through Caddy; JSON logs to stdout in prod profiles; observability = separate optional compose file (DC opts in, Cloud always on); no obs service publishes a host port; Grafana reached via SSM port-forward only.

**Non-goals:** distributed tracing (leave an MDC slot only), log-based alerting beyond email-failure, public status page, HA/multi-node obs, per-tenant metric labels.

**Highest-risk assumption (flagged):** a single small EC2 instance (t3.small, 2 GB) may not fit app+Postgres+Caddy **plus** ~600–800 MB of observability without OOM. Mitigation: the stack is opt-in/separable and every obs service gets a `mem_limit`; recommend validating on the real box and/or moving prod to t3.medium (4 GB) before enabling Phase 2+.

## 2. Architecture
- Public traffic: `Internet → Caddy :443 → app :8080` only.
- Management/actuator on `app :9090` — internal docker network only, never via Caddy, never published.
- Prometheus scrapes `app:9090/actuator/prometheus`, node-exporter:9100, cadvisor:8080, postgres-exporter:9187, loki self.
- Alloy reads the docker socket (ro) → pushes all container stdout to Loki.
- Grafana queries Prometheus (PromQL) + Loki (LogQL), runs unified alerting.
- All obs services on an `observability` docker network with app/postgres joined; **no host ports**. Operator reaches Grafana via SSM `AWS-StartPortForwardingSession`.

## 3. Components — image / purpose / RAM / retention

| Service | Image (pin, never `latest`) | Purpose | mem_limit | Retention |
|---|---|---|---|---|
| prometheus | `prom/prometheus:v3.x` | metrics TSDB+scrape+rules | 256m | `retention.time=15d`, `retention.size=2GB`; vol `prometheus_data` |
| loki | `grafana/loki:3.x` | log store (single-binary FS) | 256m | `retention_period 168h` via compactor; vol `loki_data` |
| alloy | `grafana/alloy:v1.x` | docker stdout → Loki | 128m | positions vol `alloy_data` |
| grafana | `grafana/grafana:11.x` | dashboards+explore+alerts | 128m | provisioned; vol `grafana_data` |
| node-exporter | `prom/node-exporter:v1.x` | host CPU/RAM/disk | 32m | scraped |
| cadvisor | `gcr.io/cadvisor/cadvisor:v0.49.x` | per-container CPU/RAM/IO | 128m | scraped |
| postgres-exporter | `quay.io/prometheuscommunity/postgres-exporter:v0.15.x` | DB conns/size/cache/locks | 32m | scraped |

Total obs RAM ceiling ≈ 700 MB. 15d metrics / 7d logs bounds disk to a few GB.

## 4. App instrumentation
### 4.1 pom.xml (Boot 4: actuator is a separate starter; not pulled by starter-web — confirmed absent today)
```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
<dependency>
  <groupId>io.micrometer</groupId>
  <artifactId>micrometer-registry-prometheus</artifactId>
  <!-- version managed by the Spring Boot BOM (already the <parent>); do NOT pin -->
</dependency>
```
Auto-provides: `http.server.requests`, JVM/GC/memory/threads, `hikaricp.*`, `logback_events_total`, Tomcat, system/process. Compiled into the single JAR for both modes — DC vs Cloud differ only in exposure/scraping, gated by properties, never forked.

### 4.2 Management port + exposure (base `application.properties` — same for both modes)
```properties
management.server.port=${MANAGEMENT_PORT:9090}
management.endpoints.web.exposure.include=${MANAGEMENT_ENDPOINTS:health,prometheus,info}
management.prometheus.metrics.export.enabled=${METRICS_EXPORT_ENABLED:true}
management.metrics.tags.application=hamstrack
management.metrics.tags.deployment=${SPRING_PROFILES_ACTIVE:cloud}
management.metrics.distribution.percentiles-histogram.http.server.requests=true
management.metrics.distribution.slo.http.server.requests=50ms,100ms,200ms,500ms,1s
management.endpoint.health.show-details=always
management.info.env.enabled=false
```
**SecurityConfig impact:** the app has one `SecurityFilterChain` on :8080. Add a **second, higher-priority chain bound to the management port** (`EndpointRequest.toAnyEndpoint()` + management-port matcher) that permits all — safe because :9090 is unpublished and only Prometheus in-network reaches it. Mirror the existing `.dispatcherTypeMatchers(ERROR, ASYNC).permitAll()` and CSRF-disable. Do **not** add `/actuator/**` to the 8080 chain's permitAll (that would expose it publicly).

### 4.3 `common.observability` package: a `MeterRegistryCustomizer` (if needed beyond tags) + a single `@Component ProductMetrics` holding all custom meter handles, injected into services (keeps names/labels auditable in one place).

## 5. Custom Micrometer metrics
**Cardinality/privacy rule (critical):** NEVER label by `workspaceId`/`userId`/`email`/`issueId` or any unbounded id — explodes series and leaks tenant data into the metrics store. Only bounded enum labels (`outcome`, `reason`, `kind`, `type`, `phase`, `source`).

### 5.1 Counters/summaries (emit at event site)
| Metric | Type | Labels | Emit site |
|---|---|---|---|
| `hamstrack.users.registered` | Counter | — | `AuthService.register` after `userRepository.save` |
| `hamstrack.auth.login` | Counter | `outcome`(success/failure), `reason`(bad_credentials/not_verified/disabled/ok) | `AuthService.login` — success after `resetLoginFailures`; failure in `recordLoginFailure` branch + PENDING/DISABLED branches |
| `hamstrack.auth.email_verified` | Counter | — | `AuthService.verifyEmail` after status→ACTIVE |
| `hamstrack.auth.password_reset` | Counter | `phase`(requested/completed) | `AuthService.forgotPassword` (only when user found) / `resetPassword` |
| `hamstrack.ratelimit.hit` | Counter | `kind`(ip_window/login_backoff) | `RateLimitService` where `RateLimitedException` is thrown in `checkAuthRequestAllowed` / `checkLoginAllowed` |
| `hamstrack.workspaces.created` | Counter | `source`(user/onboarding/demo) | `WorkspaceService.create(actor,req,completesOnboarding)` — derive from flag (demo seeder passes false) |
| `hamstrack.projects.created` | Counter | — | `ProjectService.create` |
| `hamstrack.issues.created` | Counter | `type`=issue-type name (bounded catalog) | `IssueService.create` after save |
| `hamstrack.invites.sent` | Counter | — | `WorkspaceService.inviteMember` after `inviteRepository.save` |
| `hamstrack.invites.accepted` | Counter | — | private `WorkspaceService.acceptInvite(actor, WorkspaceInvite)` (single choke point for token+id flows) |
| `hamstrack.invites.declined` | Counter | — | `WorkspaceService.declineInvite` |
| `hamstrack.email.sent` | Counter | `type`(verification/password_reset/invite), `outcome`(success/failure) | `MailService` `send`/`sendHtml` — needs try/catch, see 5.4 |
| `hamstrack.attachments.uploaded` | Counter | — | `AttachmentService.upload` after `fileStorage.store` |
| `hamstrack.attachments.bytes` | DistributionSummary | — | `AttachmentService.upload` `.record(file.getSize())`; `_sum` = cumulative bytes |

### 5.2 Gauges (scrape-time, via `Gauge.builder(...)` in `ProductMetrics`)
`hamstrack.users.total` (`userRepository.count()`), `hamstrack.users.active` (`UserRepository.countByStatus(ACTIVE)` — new method), `hamstrack.workspaces.total`, `hamstrack.projects.total`, `hamstrack.issues.total`.

> "Active users": ship both the login-rate proxy (`hamstrack.auth.login{outcome=success}` via `increase()`) and the ACTIVE-status gauge. True distinct-DAU needs a `last_seen_at` column — deferred (see §13).

### 5.3 Auto metrics for dashboards: `http_server_requests_seconds_*` (status/method/uri/outcome), `jvm_*`, `hikaricp_connections_*`, `logback_events_total{level}`.

### 5.4 Emission gotchas: counter increment is in-memory (no JPA flush) so no `@Version` double-flush, but still place `.increment()` after the final save; `MailService` is `@Async void` and throws — wrap `send`/`sendHtml` in try/catch to record `outcome=failure` then rethrow (unchanged behavior); counters aren't transactional (accept minor rollback drift; gauges are the source of truth for totals); don't count logins in `AuthController.withDemoSeed` — the metric belongs in `AuthService`.

## 6. Logging
`logback-spring.xml` (new, `src/main/resources/`), `<springProfile>`-gated: default/local = human-readable console; `dc` and `cloud` = one-JSON-object-per-line to stdout with `timestamp,level,logger,thread,message,stack_trace,application,deployment` + MDC slot for future `requestId`/`traceId`. Prefer Logback's built-in JSON encoder (no new dep); add `logstash-logback-encoder` only if needed. Levels via `LOG_LEVEL`/`LOG_LEVEL_APP`. Never log tokens/passwords/JWTs/full emails at INFO.
Alloy labels (bounded only): `container`, `compose_project`, `level`, `deployment`. No request/user/workspace labels. Docker socket mounted `:ro`.

## 7. `docker-compose.observability.yml`
Separate file layered onto prod compose; defines `observability` network with app/postgres joined; no `ports:` on any obs service. Per-service: prometheus (mounts `./observability/prometheus.yml`, retention args, `prometheus_data`, 30s scrape); loki (`loki-config.yml`, compactor retention, `loki_data`); alloy (`config.alloy` + `/var/run/docker.sock:ro` + `alloy_data`, depends_on loki); grafana (provisioning dir, `GF_SECURITY_*`, `GF_USERS_ALLOW_SIGN_UP=false`, analytics off, `grafana_data`, 127.0.0.1-only bind if any); node-exporter; cadvisor; postgres-exporter (DSN from DB creds or a monitoring role). Config files live under `/opt/hamstrack/observability/` (server file set, like `Caddyfile`).

## 8. Env-var wiring (dc-cloud-guard checklist)
New base `application.properties` vars: `MANAGEMENT_PORT`(9090), `MANAGEMENT_ENDPOINTS`(health,prometheus,info), `METRICS_EXPORT_ENABLED`(true), `LOG_LEVEL`(INFO), `LOG_LEVEL_APP`(INFO). Compose-only (`docker-compose.observability.yml` + `.env.prod.example` + README/self-hosting): `GF_SECURITY_ADMIN_USER`(admin), `GF_SECURITY_ADMIN_PASSWORD`(required, `${...:?}`), `GF_SERVER_ROOT_URL`, `PROMETHEUS_RETENTION_TIME`(15d), `PROMETHEUS_RETENTION_SIZE`(2GB), `LOKI_RETENTION_PERIOD`(168h), `OBS_ALERT_EMAIL_TO`(empty). No new var for `deployment` tag (reuses `SPRING_PROFILES_ACTIVE`). No `dc`/`cloud` property overrides — instrumentation is identical; only the stack differs operationally (compose concern), satisfying "no forked logic". `MANAGEMENT_PORT` never gets a compose `ports:` entry.

## 9. Dashboards (provisioned JSON)
App Overview; JVM & DB; Postgres; Host & Containers; Product/Business; Logs.

## 10. Alerts (Grafana unified alerting, email via `OBS_ALERT_EMAIL_TO`)
AppDown (`up==0`, 2m, crit); HighErrorRate (5xx>5%, 5m, crit); HighLatency (p95>1s, 10m, warn); DiskFilling (<15% free, 10m, crit); DBConnectionsSaturated (5m, warn); PostgresDown (`pg_up==0`, 2m, crit); EmailFailures (`increase(hamstrack_email_sent_total{outcome="failure"}[15m])>0`, warn); RateLimitSpike (info); JVMHeapPressure (>90%, 10m, warn); CertExpirySoon (optional, deferred).

## 11. Security
Management port never public (no `ports:`, not in Caddyfile; dedicated mgmt chain permits in-network scrape only); actuator surface minimized (health/prometheus/info only; env/heapdump/threaddump/shutdown/loggers-write off); no obs service published (SSM-only Grafana; 127.0.0.1 binds if any); Grafana admin pw via `.env` fail-fast, signup off, analytics/update-check off; **Alloy docker socket is the highest-privilege grant — mount `:ro`, pin image, document, note docker-socket-proxy as hardening follow-up**; cAdvisor broad mounts documented; postgres-exporter should use a read-only `pg_monitor` role, not the app user; metrics/logs must not carry tenant PII (cardinality rule doubles as privacy control — matters most in multi-tenant Cloud); retention caps bound sensitive-data lifetime.

## 12. DC vs Cloud + deploy change
Instrumentation identical in both modes (compiled in, same defaults). Stack differs operationally: **Cloud/prod always runs it** — update `deploy.yml` SSM `commands` to layer both files in `pull` and `up -d`: `docker compose -f docker-compose.prod.yml -f docker-compose.observability.yml pull && ... up -d --remove-orphans && docker image prune -f`. The `observability/` config dir + the obs compose file must be present on the server (server file set, out-of-band from image deploys). **`--remove-orphans` caveat:** never run `up --remove-orphans` with only the prod file once the stack is up (it would delete obs containers) — always pass both `-f`. **DC self-hosters opt in** by adding the second `-f`; omitting it leaves the app fully working (actuator just unscraped).

## 13. Open questions (with recommended defaults)
1. "Active users" — ship login-rate proxy + ACTIVE gauge now; no `last_seen_at` column yet (recommend proxy first, flag stakeholder expectation of distinct-DAU).
2. Alert channel — email now; webhook stub later.
3. Grafana hardening — SSM + admin pw sufficient; no OAuth now.
4. Config-file delivery — manual `/opt/hamstrack/observability/` bootstrap; repo-sync a follow-up.
5. Instance size — confirm/upgrade to t3.medium before Phase 2+, or rely on `mem_limit`s.
6. postgres-exporter — read-only `pg_monitor` role, not app user.

## 14. SSM port-forward runbook
No published Grafana port/subdomain. With owner AWS creds (`ssm:StartSession`): `aws ssm start-session --target i-019fe684b25ad831f --document-name AWS-StartPortForwardingSession --parameters '{"portNumber":["3000"],"localPortNumber":["3000"]}'` (if grafana binds `127.0.0.1:3000`), else `AWS-StartPortForwardingSessionToRemoteHost` to the grafana container. Browse `http://localhost:3000`, log in with `GF_SECURITY_*`. Same pattern for Prometheus/Loki debugging. Document in `docs/ops-prod-hardening.md`.

## 15. Phased rollout & acceptance criteria
- **Phase 1 — Logging + log pipeline:** `logback-spring.xml` JSON in prod profiles / readable local; obs compose with loki+alloy+grafana only; Loki datasource + Logs dashboard; SSM runbook. AC: readable local, JSON-per-line in cloud/dc, stack up with no published ports, Grafana Explore shows app/postgres/caddy logs by container+level, no secrets in logs, Loki retention set.
- **Phase 2 — Infra metrics:** add prometheus+node-exporter+cadvisor+postgres-exporter; scrape config; Host & Containers + Postgres dashboards. AC: targets UP, host+container panels populate, Postgres panels populate, `mem_limit` on each, total obs RSS ≤ ~700 MB.
- **Phase 3 — App technical metrics:** pom deps; §4.2 props; mgmt SecurityFilterChain; `hamstrack-app` scrape; App Overview + JVM & DB dashboards. AC: `/actuator/prometheus` on :9090 in-network only, **:8080 does NOT expose `/actuator/**`**, Caddy doesn't proxy 9090, exposure limited to health/prometheus/info, dashboards populate, test suite green.
- **Phase 4 — Business metrics:** `ProductMetrics`; wire counters/gauges into AuthService/RateLimitService/WorkspaceService/ProjectService/IssueService/MailService/AttachmentService; `UserRepository.countByStatus`; Product dashboard. AC: register/login/create/invite/email/attachment counters+gauges move; **no id labels**; counters emit after final save; MailService records failure without behavior change.
- **Phase 5 — Alerts:** rules + email contact point; Grafana SMTP; `deploy.yml` layers obs file; env vars in `.env.prod.example` + README/self-hosting. AC: AppDown fires/resolves; simulated 5xx/disk/email-failure fire; deploy leaves obs running (no orphan removal); every new env var wired end-to-end.

## Sources
- https://www.baeldung.com/spring-boot-prometheus
- https://oneuptime.com/blog/post/2026-01-26-prometheus-metrics-micrometer-spring/view
