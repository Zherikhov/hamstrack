# Stage 1: build
# Uses full JDK image to compile and package the application
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app

# Version stamped into the JAR via build-info (About dialog / /api/meta).
# CI passes the release version (git tag) here; defaults to a dev marker so
# a plain `docker build` still works. pom.xml itself holds a 0.0.0-DEV placeholder.
ARG APP_VERSION=0.0.0-DEV

# Copy Maven wrapper and pom first — Docker caches this layer
# so dependencies are only re-downloaded when pom.xml changes
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw
RUN ./mvnw dependency:go-offline -B

# Now copy source, stamp the version, and build
COPY src/ src/
RUN ./mvnw -B versions:set -DnewVersion="${APP_VERSION}" -DgenerateBackupPoms=false \
 && ./mvnw clean package -DskipTests -B

# Stage 2: run
# Smaller JRE-only image — no compiler, no source code
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
