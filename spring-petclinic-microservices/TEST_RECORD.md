# Test Record

## Date: 2026-02-12

## Build

```
mvn clean package -DskipTests
```

Result: BUILD SUCCESS (all 7 modules, ~34s)

## Startup (without LR4J agent, without Docker/Zipkin)

Started services manually in order:
1. Config server (port 8888) - started, health 200 after ~20s
2. Discovery server (port 8761) - started, health 200 after ~20s
3. Customers service (port 8081) - started, health 200 after ~25s
4. Visits service (port 8082) - started, health 200
5. Vets service (port 8083) - started, health 200
6. API gateway (port 8080) - started, health 200

All 6 services came up successfully without Docker/Zipkin (Zipkin connection warnings in logs, but services work fine).

## Bug Verification

### Direct via customers service (port 8081)

```
Fetch 1: telephone: +1-6085551023
Fetch 2: telephone: +1-+1-6085551023
Fetch 3: telephone: +1-+1-+1-6085551023
Fetch 4: telephone: +1-+1-+1-+1-6085551023
Fetch 5: telephone: +1-+1-+1-+1-+1-6085551023
```

Each GET request to `/owners/1` adds another `+1-` prefix. Bug confirmed.

### Via API gateway (port 8080)

```
Fetch 1: telephone: +1-6085551749
Fetch 2: telephone: +1-+1-6085551749
```

Bug also manifests when accessing through the API gateway. Confirmed.

### Root cause

`OwnerResource.findOwner()` calls `normalizePhone()` which modifies the JPA-managed
entity's telephone field. The guard `!tel.startsWith("1-")` doesn't match the `"+1-"`
prefix it adds (starts with `+`, not `1`), so it re-applies on every read.
Because the method is `@Transactional`, JPA dirty-checking flushes the change to the DB.

## Issues Found During Testing

1. **`lr4j-record-1.0.so` path was wrong** - Script referenced `lr4j-record-1.0.so` but
   actual file is `lr4j_agent_x64.so`. Fixed in `run_all.sh`.

2. **`@Digits` validation on telephone field** - Original `@Digits(fraction=0, integer=12)`
   rejected the `+1-` prefix (non-digit chars). Removed the constraint since international
   phone numbers legitimately contain `+` and `-`.

3. **`VARCHAR(12)` column too narrow** - HSQLDB schema had `telephone VARCHAR(12)` which
   truncated `+1-6085551023` (13+ chars). Widened to `VARCHAR(255)`.

4. **`@Transactional` required** - Without it, the entity detaches after the repository
   call and dirty-checking doesn't flush. Added `@Transactional` to `findOwner()`.
