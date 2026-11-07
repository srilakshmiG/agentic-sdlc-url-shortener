# syntax=docker/dockerfile:1
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -q -B -DskipTests package

# Used by `docker compose --profile test run --rm tests`: unit tests plus integration tests against compose services.
FROM build AS tests
CMD ["mvn", "-B", "-q", "test"]

FROM eclipse-temurin:25-jre AS runtime
RUN useradd --system --create-home app
WORKDIR /app
COPY --from=build /build/target/app.jar /app/app.jar
# The brownfield codebase agent reads this read-only copy of the real source tree.
COPY src/main /app/codebase
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
