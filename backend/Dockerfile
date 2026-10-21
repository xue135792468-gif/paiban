FROM eclipse-temurin:21-jdk AS build
WORKDIR /build
COPY . .
RUN ./mvnw -B -DskipTests package || mvn -B -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends fonts-noto-cjk curl gosu \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 paiban \
    && useradd --uid 10001 --gid paiban --create-home --shell /bin/bash paiban
COPY --from=build /build/target/*.jar app.jar
COPY entrypoint.sh /app/entrypoint.sh
COPY secret-bootstrap.sh /app/secret-bootstrap.sh
RUN mkdir -p /app/uploads /app/data \
    && chmod +x /app/entrypoint.sh /app/secret-bootstrap.sh \
    && chown -R paiban:paiban /app
EXPOSE 8080
# Spring Boot needs ~30-60s to boot; start-period keeps the container from being
# marked unhealthy before the first check can succeed.
HEALTHCHECK --interval=5s --timeout=3s --retries=12 --start-period=60s \
  CMD curl -fsS http://localhost:8080/api/health || exit 1
ENTRYPOINT ["/app/entrypoint.sh"]
