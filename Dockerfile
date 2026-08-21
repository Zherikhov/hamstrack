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
#
# Bound the heap (HD-152). A bare `java -jar` leaves heap sizing to ergonomics, which claims
# ~25% of whatever memory the JVM can see - so the budget a request allocates against was a
# property of the machine somebody happened to deploy on, and nothing in this repository
# related it to what one request may allocate. app.reports.max-rows is reasoned in bytes
# against a 512 MB heap (see ReportProperties); this line and the container limit beside it
# in docker-compose.prod.yml are what make that figure a fact rather than an assumption.
#
# A PERCENTAGE, not -Xmx, deliberately: -XX:MaxRAMPercentage is read against the cgroup
# limit (UseContainerSupport, on by default), so the heap follows whatever memory the
# container is given and the operator's dial is the container limit - one number, in the
# compose file, on the box being sized. An absolute -Xmx would be correct for exactly one
# container size and silently wrong for every other, and this same image runs a 1 GB
# self-hosted box and a larger Cloud one.
#
# The percentage alone fixes nothing: with no container limit it is a percentage of HOST
# RAM, and at 50 that would be worse than the ~25% it replaces. The two halves ship
# together - docker-compose.prod.yml sets mem_limit (APP_MEMORY_LIMIT, default 1g), and
# docs/self-hosting.md tells an operator running their own compose to set one too.
#
# 50% of the default 1 GB limit is a 512 MB heap - the reference figure exactly. The other
# 512 MB is not slack: metaspace, the code cache, thread stacks (Tomcat's request pool is
# capped at 200 threads by default, ~1 MB of stack each), GC bookkeeping, direct buffers and
# the allocator's own overhead all live OUTSIDE the heap, and a percentage set close to the
# limit does not produce an OutOfMemoryError - it produces a kernel OOM kill (exit 137, no
# stack, no handler) once heap + native crosses the cgroup limit.
#
# Half is headroom sized for a SMALL limit, and it does NOT stay right as the limit grows,
# because most of that non-heap need is CONSTANT rather than proportional: metaspace and the
# code cache land in the low hundreds of MB whatever the heap is, and thread stacks are
# bounded by server.tomcat.threads.max, not by heap. Only GC bookkeeping (~1-3%) really
# scales. So at a 4g limit this reserves ~1.5 GB nobody can use, and the answer there is an
# explicit heap (JAVA_TOOL_OPTIONS=-Xmx..., roughly the limit minus ~700 MB) rather than a
# bigger number here - this flag is a per-image constant and cannot know one operator's box.
#
# IDENTICAL FOR BOTH DEPLOYMENT MODES, by the same rule as the locale pin above: how much
# memory a JVM may use is a property of the box it runs on, not of the plan somebody bought,
# so it is expressed as a percentage of that box and never gated on SPRING_PROFILES_ACTIVE.
# A mode-gated heap would mean a DC out-of-memory could not be reasoned about from Cloud.
#
# Unlike the locale pin, this one IS overridable from the environment, deliberately, since
# 50% is only the right split near the default limit. But ONLY in the -Xmx form: an explicit
# -Xmx always beats MaxRAMPercentage (ergonomics consults the percentage only when max heap
# was not set outright), so JAVA_TOOL_OPTIONS=-Xmx1500m wins over this line. Setting the
# SAME flag there does NOT win: JAVA_TOOL_OPTIONS is read before the command line, so this
# copy takes effect - and the JVM still prints "Picked up JAVA_TOOL_OPTIONS: ..." on stderr,
# which acknowledges that it READ the variable, not that it applied it. An operator raising
# the limit reaches for the flag they just read in the docs, gets exactly the confirmation
# line they were looking for, and gets no extra heap. Both forms measured, not assumed.
#
# THIS LINE OWNS THAT WARNING, and the warning is repeated wherever an operator might set
# the variable: docker-compose.prod.yml, .env.prod.example, and TWICE in
# docs/self-hosting.md - the APP_MEMORY_LIMIT row of the configuration table and the
# callout in "The heap is bounded from 0.17.0". Changing this flag means walking all of
# them, because each one names it. (The byte-budget half of this setting has its own owner:
# application.properties points at ReportProperties for the rows-to-heap arithmetic.)
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=50.0", "-Duser.language=en", "-Duser.country=US", "-jar", "app.jar"]
