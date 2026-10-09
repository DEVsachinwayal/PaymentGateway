# One Dockerfile for every Java service:  docker build --build-arg SERVICE=payment-service -t apg/payment-service:dev .
# Multi-stage: Maven build (tests run in CI, not here) -> slim JRE, non-root, health check on the actuator port.
FROM maven:3.9-eclipse-temurin-21 AS build
ARG SERVICE
WORKDIR /src
COPY pom.xml .
COPY libs libs
COPY services services
RUN test -n "$SERVICE" && mvn -q -B -DskipTests -pl services/${SERVICE} -am package

FROM eclipse-temurin:21-jre-alpine
ARG SERVICE
# Port the service listens on and the port its actuator health is served on (the gateway uses a separate management port)
ARG PORT=8080
ARG HEALTH_PORT=8080
ENV SERVER_PORT=${PORT} HEALTH_PORT=${HEALTH_PORT} JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError"
RUN addgroup -S apg && adduser -S -G apg apg
WORKDIR /app
COPY --from=build /src/services/${SERVICE}/target/${SERVICE}-*.jar /app/app.jar
USER apg
EXPOSE ${PORT}
HEALTHCHECK --interval=15s --timeout=3s --start-period=60s --retries=5 \
  CMD wget -qO- "http://127.0.0.1:${HEALTH_PORT}/actuator/health" | grep -q '"status":"UP"' || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
