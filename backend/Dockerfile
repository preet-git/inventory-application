# syntax=docker/dockerfile:1

# Build with a full JDK, ship on a JRE. The compiler, the Maven distribution and the unpacked
# dependency tree all stay in this stage; none of it reaches the published image.
FROM eclipse-temurin:25-jdk AS build
WORKDIR /app

# The wrapper and the pom on their own first, so editing a source file does not invalidate the
# dependency download. maven-wrapper.properties pins the Maven version, which is what makes this
# reproducible rather than "whatever Maven the base image happened to carry".
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./

# git on Windows carries neither the executable bit nor guaranteed LF endings, and the wrapper is
# a POSIX shell script. Without this the build fails with a bare "bad interpreter".
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw

RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -ntp dependency:go-offline

COPY src/ src/
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -ntp clean package -DskipTests

# Explode the jar along its layer boundaries. Dependencies change far less often than application
# classes, so unpacking them into separate COPY layers below means a code-only rebuild re-uploads
# a few hundred KB rather than the whole ~75 MB archive.
#
# target/*.jar matches only the boot jar: the pre-repackage original is left as .jar.original.
# Copying it aside keeps this line independent of the version in the pom.
RUN cp target/*.jar app.jar \
    && java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted


FROM eclipse-temurin:25-jre-alpine AS runtime
WORKDIR /app

# Unprivileged. Nothing writes into the image at runtime; uploads are staged under /tmp, which
# stays writable, and the database holds everything that outlives a request.
RUN addgroup -S app && adduser -S -G app app

# One COPY per layer, ordered least- to most-frequently changed. The contents merge into /app --
# the loader classes land at org/, the application at BOOT-INF/ -- which is the layout JarLauncher
# expects on the default classpath.
COPY --from=build --chown=app:app /app/extracted/dependencies/ ./
COPY --from=build --chown=app:app /app/extracted/spring-boot-loader/ ./
COPY --from=build --chown=app:app /app/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=app:app /app/extracted/application/ ./

USER app
EXPOSE 8080

# Container-aware heap sizing: the percentage is of the container's limit, not the host's RAM. POI
# holds a whole workbook in memory while reading it, so an OOM here should kill the container and
# let the restart policy deal with it rather than limp on in a half-dead state.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

# busybox wget: the alpine image has no curl, and pulling one in for a healthcheck is not worth it.
# 127.0.0.1 rather than localhost, which busybox resolves to [::1] first -- Tomcat binds dual-stack
# so the name works today, but the literal does not depend on that.
#
# /api/products?size=1 rather than /api/products/summary -- the summary aggregates the entire table
# and is far too expensive to run every 15 seconds, while this is a two-row read down the
# (product_sku, purchase_date) unique index. It covers the database as well as the web layer: the
# app cannot answer it if Postgres is gone. start-period is generous because the first boot runs
# the Flyway migrations before the port opens.
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=3 \
    CMD wget -q -O /dev/null "http://127.0.0.1:8080/api/products?size=1" || exit 1

# Shell form so $JAVA_OPTS is expanded, exec so java replaces the shell as PID 1 and receives the
# SIGTERM from docker stop instead of being killed on the timeout.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
