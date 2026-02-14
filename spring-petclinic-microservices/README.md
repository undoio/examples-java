# Spring PetClinic Microservices - Undo LiveRecorder Demo

A microservices version of the Spring PetClinic sample application, instrumented with
Undo LiveRecorder for on-demand recording and time-travel debugging.

Based on [spring-petclinic-microservices](https://github.com/spring-petclinic/spring-petclinic-microservices) (v2.6.7).

## Architecture

| Service | Port | Purpose |
|---------|------|---------|
| Config Server | 8888 | Centralised configuration |
| Discovery Server (Eureka) | 8761 | Service registry |
| API Gateway | 8080 | Frontend UI and request routing |
| Customers Service | 8081 | Owner and pet management |
| Visits Service | 8082 | Visit records |
| Vets Service | 8083 | Veterinarian data |
| Zipkin | 9411 | Distributed tracing |

The API Gateway and Customers Service are instrumented with UndoLR for on-demand recording.

## Prerequisites

- Java 8+
- Maven 3.6+
- Undo LiveRecorder (`LR4J_HOME` pointing to the directory containing `lr4j-record-1.0/`)
- Docker and docker compose (optional, for Zipkin distributed tracing)

## Build

**First-time setup**: Copy the LiveRecorder API JAR from your Undo installation:

```bash
cp ${LR4J_HOME}/lr4j-record-1.0/lr4j_api-1.1.jar .
```

This JAR is required for the recording control API used by the instrumented services.

Then build the project:

```bash
mvn clean package -DskipTests
```

## Run

```bash
export LR4J_HOME=/path/to/lr4j
scripts/run_all.sh
```

This starts Zipkin via docker compose, then launches the Java services. The customers
service and API gateway are started with the LiveRecorder agent.

Once running, access the Pet Clinic at http://localhost:8080

## Stop

```bash
pkill -f spring-petclinic
docker compose kill   # if Zipkin was started
```

## Demo: Finding a Data Corruption Bug

This demo contains a subtle bug: viewing an owner's details corrupts their phone number.
Each time you view the same owner, the phone number gets an extra `+1-` prefix prepended.

### Steps

1. **Start the application** using `scripts/run_all.sh`

2. **Start recording** both services:
   ```bash
   curl 'http://localhost:8081/owners/startRecording'
   curl 'http://localhost:8080/api/gateway/startRecording'
   ```

3. **Browse the Pet Clinic** at http://localhost:8080
   - Click "Find owners" → "Find" to list all owners
   - Click on an owner (e.g. George Franklin) - note the phone number
   - Go back and click the same owner again - the phone number has changed!
   - Each view adds another `+1-` prefix: `6085551023` → `+1-6085551023` → `+1-+1-6085551023`

4. **Save the recordings**:
   ```bash
   curl 'http://localhost:8081/owners/saveRecording/customers-service.undo'
   curl 'http://localhost:8080/api/gateway/saveRecording/api-gateway.undo'
   ```

### Debugging with Time Travel

The bug is that a GET (read-only!) endpoint is silently modifying data in the database.
A conventional debugger won't help - there's no exception, no error log, just corrupted data.

With Undo's time-travel debugger you can:
1. Set a watchpoint on the `telephone` field
2. Travel back to the exact moment it was modified
3. Discover the root cause: a phone normalization method modifies the JPA entity directly,
   and Hibernate's dirty-checking silently persists the change

### Replay

Start two replay sessions:
```bash
lr4j-replay-1.0/lr4j/lr4j_replay --input api-gateway.undo --port 9000
lr4j-replay-1.0/lr4j/lr4j_replay --input customers-service.undo --port 9001
```

In IntelliJ, create two Remote JVM Debug configurations:
- **API Gateway** - port 9000
- **Customers Service** - port 9001

### Step Across: Debugging Across Microservices

With both recordings loaded you can step from the API gateway into the customers service:

1. Open `ApiGatewayController.java` and set a breakpoint at `getOwnerDetails`
2. Debug the **API Gateway** configuration (port 9000) - you'll hit the breakpoint
3. Debug the **Customers Service** configuration (port 9001) - it runs to end
4. In the API Gateway debug session, step to the line that calls the customers service REST API
5. Click **Step Across** - this locates the corresponding position in the customers service recording
6. You are now in `OwnerResource.findOwner()` in the customers service, with the correct `ownerId`

This lets you trace a request across microservice boundaries as if it were a single application.

## UndoLR Integration

Recording is controlled via REST endpoints added to `OwnerResource.java` and `ApiGatewayController.java`:

- `GET /owners/startRecording` - starts recording the customers service
- `GET /owners/saveRecording/<filename>` - saves and stops recording
- `GET /api/gateway/startRecording` - starts recording the API gateway
- `GET /api/gateway/saveRecording/<filename>` - saves and stops recording

The `scripts/run_all.sh` script starts the instrumented services with the required JVM arguments:
```
-XX:-Inline -XX:TieredStopAtLevel=1 -agentpath:${LR4J_HOME}/lr4j-record-1.0/lr4j_agent_x64.so
```
