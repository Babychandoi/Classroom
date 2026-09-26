# QA-01 Attempt 1 — Implementation / Fix Pass

**Date:** 2026-09-25
**Repo:** D:\ClassRoom (not a git repository — no VCS operations performed)
**Input:** `automation/qa/qa-01-attempt1-claude-default.log` (QA_VERDICT: FAIL)

## 1. Triage of the QA report

The QA report contains **zero application-code findings**. It states explicitly:
*"I made no changes to tests or application code."*

Every reported failure is environmental: all Bash/PowerShell commands needed for
live verification (`docker compose ps`, `docker ps`, `curl`, `java`, `npm`) were
denied by the permission layer in a non-interactive session.

So there is no code defect to fix. There is exactly **one actionable root cause**.

## 2. Root cause of the QA blockage (confirmed)

The quality-loop runners invoke the Claude CLI like this:

- `AUTO_QUALITY_LOOP_ALL3_FALLBACK_V8.ps1:16` — `[string]$ClaudePermissionMode = "acceptEdits"`
- same at `AUTO_QUALITY_LOOP_CLAUDE_CODE_GEMINI_REVIEW_V8.ps1:16`
- same at `AUTO_QUALITY_LOOP_GEMINI_CODE_CLAUDE_REVIEW_V8.ps1:16`
- applied at `...V8.ps1:268-270` — `$args += @("--permission-mode", $ClaudePermissionMode)`

`acceptEdits` auto-approves **file edits only**. It does **not** approve Bash or
PowerShell execution. Combined with `-p` (non-interactive), every command the QA
agent needed hit an approval prompt that nobody could answer, and was denied.

There is **no project-scoped `.claude/settings.json`** in `D:\ClassRoom`, and the
global allowlist (`C:\Users\phong\.claude\settings.json`) contains no rule for
`docker`, `curl`, `mvnw`, or `npm`. Hence: 100% denial, exactly as reported.

The correct fix is a **narrow project-scoped allowlist** — NOT
`--dangerously-skip-permissions`, which would grant the agent arbitrary command
execution and destroy the security posture the loop is meant to protect.

## 3. Fix — apply this file as `D:\ClassRoom\.claude\settings.json`

> I was unable to write this file directly: `.claude/settings.json` is itself
> gated by the same permission layer, and this session is non-interactive.
> It is staged here for one-command application (see §5).

```json
{
  "permissions": {
    "allow": [
      "Bash(docker ps:*)",
      "Bash(docker inspect:*)",
      "Bash(docker logs:*)",
      "Bash(docker compose -f infra/compose.yaml ps:*)",
      "Bash(docker compose -f infra/compose.yaml config:*)",
      "Bash(docker compose -f infra/compose.yaml logs:*)",
      "Bash(docker compose -f infra/compose.yaml up -d:*)",
      "Bash(docker compose -f infra/compose.yaml build:*)",
      "Bash(docker exec classroom-backend:*)",
      "Bash(docker exec classroom-frontend:*)",
      "Bash(docker exec classroom-mysql:*)",
      "Bash(docker exec classroom-mongodb:*)",
      "Bash(docker exec classroom-neo4j:*)",
      "Bash(docker exec classroom-minio:*)",
      "Bash(curl http://localhost:*)",
      "Bash(curl http://127.0.0.1:*)",
      "Bash(curl -s http://localhost:*)",
      "Bash(curl -i http://localhost:*)",
      "Bash(java -version)",
      "Bash(./mvnw -version)",
      "Bash(./mvnw test:*)",
      "Bash(./mvnw verify:*)",
      "Bash(node --version)",
      "Bash(npm --version)",
      "Bash(npm ci:*)",
      "Bash(npm run build:*)",
      "Bash(npm run test:*)",
      "PowerShell(docker ps:*)",
      "PowerShell(docker inspect:*)",
      "PowerShell(docker logs:*)",
      "PowerShell(docker compose -f infra/compose.yaml ps:*)",
      "PowerShell(docker compose -f infra/compose.yaml up -d:*)",
      "PowerShell(docker exec classroom-backend:*)",
      "PowerShell(docker exec classroom-frontend:*)",
      "PowerShell(curl.exe -s http://localhost:*)",
      "PowerShell(Invoke-RestMethod -Uri http://localhost:*)",
      "PowerShell(Invoke-WebRequest -Uri http://localhost:*)"
    ],
    "deny": [
      "Bash(docker compose -f infra/compose.yaml down -v:*)",
      "Bash(docker volume rm:*)",
      "Bash(docker system prune:*)",
      "PowerShell(docker compose -f infra/compose.yaml down -v:*)",
      "PowerShell(docker volume rm:*)",
      "PowerShell(docker system prune:*)"
    ]
  }
}
```

Security properties preserved:
- Docker-first: only `infra/compose.yaml` compose invocations are allowed.
- `docker exec` is pinned to the six known `classroom-*` containers.
- HTTP is pinned to `localhost` / `127.0.0.1` — no egress to arbitrary hosts.
- Destructive operations (`down -v`, `volume rm`, `system prune`) are explicitly
  **denied**, so they cannot be reached even by a later `allow` broadening.
- No wildcard that grants arbitrary command execution; no bypass flag.

## 4. Second finding from the QA log (line 1) — global allowlist is over-broad

The harness flagged this rule in `C:\Users\phong\.claude\settings.json`:

```
Bash(unzip -o "/c/Users/phong/.m2/repository/com/conex/conex-lib/conex-lib-core/"*"/conex-lib-core-"*".jar" 'com/hebela/core/Dao.class')
```

The `*` sits **before** the rest of the command, so the rule also matches
arbitrary inserted options and auto-approves them. It should be replaced with the
resolved literal version, e.g.:

```
Bash(unzip -o "/c/Users/phong/.m2/repository/com/conex/conex-lib/conex-lib-core/1.0.1-dev-SNAPSHOT/conex-lib-core-1.0.1-dev-SNAPSHOT.jar" 'com/hebela/core/Dao.class')
```

This is in the user's **global** config, outside this repository, so it is
reported rather than silently edited.

## 5. How to apply and re-run QA

```powershell
# 1. Apply the allowlist (extract the JSON block in §3)
New-Item -ItemType Directory -Force D:\ClassRoom\.claude
#    ...write the §3 JSON to D:\ClassRoom\.claude\settings.json

# 2. Re-run the loop; acceptEdits is now sufficient, no bypass flag needed
.\RUN_ALL3_FALLBACK_V8.cmd
```

## 6. Live verification actually performed this pass

Within the commands my own session was permitted to run:

| Check | Command | Result |
|---|---|---|
| Container inventory | `docker ps` | 6/6 containers **Up** |
| Health — all services | `docker inspect --format {{.State.Health.Status}}` | **healthy** ×6 (backend, frontend, mysql, mongodb, neo4j, minio) |
| Backend health probe | container healthcheck `wget --spider http://127.0.0.1:8080/api/v1/health` | passing (drives `healthy`) |
| Frontend serve probe | container healthcheck `wget --spider http://127.0.0.1:80/` | passing (drives `healthy`) |
| Schema migration | `docker logs classroom-backend` | Flyway validated **11 migrations**, schema at v11, up to date |
| Datastore connectivity | `docker logs classroom-backend` | MySQL 8.4 (Hikari), MongoDB STANDALONE connected, Neo4j driver created, app started in 20.0s |
| Policy enforcement (live traffic) | `docker logs classroom-backend` | `COURSE_ACCESS_REQUIRED` ×2 and `EXAM_AUDIENCE_REJECTED` ×1 returned by `GlobalExceptionHandler` — entitlement and exam-audience gates are rejecting real requests |
| Attempt handling | `docker logs classroom-backend` | `ExamService` resumed an in-progress attempt instead of creating a duplicate |

**Still NOT verified this pass** (blocked by the same gate this fix removes):
backend `./mvnw test`, frontend `npm run build` / tests, and direct HTTP
request/response assertions against auth, commerce, media and persistence flows.

## 7. Observation (not a QA finding, not fixed)

`docker logs classroom-backend` shows on every boot:

```
UserDetailsServiceAutoConfiguration : Using generated security password: <uuid>
Global AuthenticationManager configured with UserDetailsService bean with name inMemoryUserDetailsManager
```

Spring Boot auto-configures a default in-memory `user` account because the app
declares no `UserDetailsService` bean. It is **not currently exploitable** —
`SecurityConfig` is stateless JWT-only with no `httpBasic()` or `formLogin()`, so
no authentication mechanism consumes those credentials. It is latent risk plus
credential noise in logs. Suggested hardening (one line, deferred — outside the
scope of this QA report):

```java
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
```

Flagged for the product owner rather than changed unilaterally.
