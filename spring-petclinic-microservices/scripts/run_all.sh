#!/usr/bin/env bash

if [[ -z "${LR4J_HOME}" ]]; then
    echo "You must set the LR4J_HOME env var to point to the directory containing 'agent/lr4j_agent_x64.so'"
    exit
elif [ ! -f ${LR4J_HOME}/agent/lr4j_agent_x64.so ]; then
    echo "'${LR4J_HOME}/agent/lr4j_agent_x64.so' not found!"
    exit
fi

set -o errexit
set -o errtrace
set -o nounset
set -o pipefail

# -XX:TieredStopAtLevel=1 caps JIT compilation at C1, disabling the C2 compiler.
# It is used by default; set DISABLE_C2=false to allow C2 during recording.
if [[ "${DISABLE_C2:-true}" == "false" ]]; then
    C2_ARG=""
else
    C2_ARG="-XX:TieredStopAtLevel=1"
fi

pkill -9 -f "spring-petclinic.*\.jar" || echo "No existing apps to kill"

# Detect docker compose command (v2 plugin or v1 standalone)
if command -v docker-compose &> /dev/null; then
    COMPOSE="docker-compose"
elif docker compose version &> /dev/null; then
    COMPOSE="docker compose"
else
    COMPOSE=""
fi

# Start Zipkin if docker compose is available (optional - app works without it)
if [[ -n "$COMPOSE" ]]; then
    $COMPOSE kill || echo "No docker containers are running"
    echo "Starting Zipkin (tracing server)"
    $COMPOSE up -d tracing-server
else
    echo "docker compose not found - skipping Zipkin (tracing will be unavailable)"
fi

echo "Running apps"
mkdir -p target
nohup java -jar spring-petclinic-config-server/target/*.jar --server.port=8888 --spring.profiles.active=chaos-monkey > target/config-server.log 2>&1 &
echo "Waiting for config server to start"
sleep 20
nohup java -jar spring-petclinic-discovery-server/target/*.jar --server.port=8761 --spring.profiles.active=chaos-monkey > target/discovery-server.log 2>&1 &
echo "Waiting for discovery server to start"
sleep 20
nohup java -XX:-Inline ${C2_ARG} -XX:UseAVX=2 -agentpath:${LR4J_HOME}/agent/lr4j_agent_x64.so -jar spring-petclinic-customers-service/target/*.jar --server.port=8081 --spring.profiles.active=chaos-monkey > target/customers-service.log 2>&1 &
nohup java -jar spring-petclinic-visits-service/target/*.jar --server.port=8082 --spring.profiles.active=chaos-monkey > target/visits-service.log 2>&1 &
nohup java -jar spring-petclinic-vets-service/target/*.jar --server.port=8083 --spring.profiles.active=chaos-monkey > target/vets-service.log 2>&1 &
nohup java -XX:-Inline ${C2_ARG} -XX:UseAVX=2 -agentpath:${LR4J_HOME}/agent/lr4j_agent_x64.so -jar spring-petclinic-api-gateway/target/*.jar --server.port=8080 --spring.profiles.active=chaos-monkey > target/gateway-service.log 2>&1 &
echo "Waiting for apps to start"
sleep 60
