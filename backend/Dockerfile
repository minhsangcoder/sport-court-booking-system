FROM maven:3.9-eclipse-temurin-21 AS build
ARG SERVICE
WORKDIR /workspace
COPY . .
# Database integration tests run before image creation in build-backend.ps1 / CI.
# Image builds have no Docker daemon available for Testcontainers.
RUN mvn -pl "${SERVICE}" -am clean package -DskipTests

FROM eclipse-temurin:21-jre-alpine
ARG SERVICE
WORKDIR /app
COPY --from=build "/workspace/${SERVICE}/target/${SERVICE}-0.0.1-SNAPSHOT.jar" app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
