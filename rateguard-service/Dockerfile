# Runtime image for the RateGuard service.
# Build the jar first (from the repository root):  mvn -DskipTests package
FROM eclipse-temurin:25-jre

RUN groupadd --system rateguard && useradd --system --gid rateguard --no-create-home rateguard
WORKDIR /app
COPY target/rateguard-service-*.jar /app/app.jar
USER rateguard

EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
