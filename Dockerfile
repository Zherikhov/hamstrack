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
# Pin the JVM locale. NOT a preference - the default locale is derived from the container
# LANG/LC_ALL, so an operator whose host or .env carries tr_TR/az/lt gets a JVM where
# Character/String case folding maps I to a dotless i. Every fold that decides identity
# already names Locale.ROOT explicitly (HD-120); this is the second layer under those, and
# it works differently: it makes the DEFAULT boring, so a fold added tomorrow without an
# explicit locale misbehaves nowhere rather than on one class of host. Command-line -D
# beats both the environment and JAVA_TOOL_OPTIONS, so .env cannot undo it.
#
# en/US rather than a root locale, deliberately: for case folding the two are equivalent,
# and en-US additionally pins default number and date formatting for any code that formats
# without naming a locale. It is a server-side default and not the UI language - nothing a
# user reads changes - but it IS a behaviour change for an operator who was relying on the
# host locale for those, so it is a stated choice rather than a side effect.
#
# IDENTICAL FOR BOTH DEPLOYMENT MODES, and it must stay that way: dc and cloud run this
# same image and differ only by SPRING_PROFILES_ACTIVE. A locale that differed by mode
# would mean a DC upgrade could not be reasoned about from Cloud behaviour, which is the
# whole thing the single-image rule buys. This is not a profile-gated setting and there is
# no correct reason to make it one.
#
# This reaches installs that run this image, and nothing else. An install that runs the JAR
# directly gets no pin - see docs/self-hosting.md, "Duplicate accounts after an upgrade".
ENTRYPOINT ["java", "-Duser.language=en", "-Duser.country=US", "-jar", "app.jar"]
