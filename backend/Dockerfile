FROM eclipse-temurin:21-jdk AS build
WORKDIR /build
COPY . .
RUN ./mvnw -B -DskipTests package || mvn -B -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends fonts-noto-cjk \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /build/target/*.jar app.jar
RUN mkdir -p /app/uploads /app/data
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
