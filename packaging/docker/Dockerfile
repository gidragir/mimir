# syntax=docker/dockerfile:1

# ---- build: Gradle-сборка демона с web UI ----
FROM eclipse-temurin:27-jdk-alpine AS build
WORKDIR /src

# Android SDK в образе нет: Android-таргеты пропускаются, секреты не нужны.
# Все модули копируются целиком: settings.gradle.kts подключает их все, и Gradle требует их каталоги.
COPY . .

RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew :daemon:installDist --no-daemon --console=plain

# ---- runtime-image: минимальный JRE через jlink только с нужными модулями ----
FROM build AS jre
RUN set -eux; \
    modules="$(jdeps --multi-release 27 --ignore-missing-deps --print-module-deps /src/daemon/build/install/daemon/lib/*.jar)"; \
    jlink --add-modules "${modules},jdk.unsupported,java.naming,jdk.crypto.ec" \
          --strip-debug --no-header-files --no-man-pages --compress=zip-6 \
          --output /opt/jre

# ---- final ----
FROM alpine:3
RUN addgroup -S mimir && adduser -S -G mimir -h /app mimir && mkdir /vault && chown mimir:mimir /vault
COPY --from=jre /opt/jre /opt/jre
COPY --from=build /src/daemon/build/install/daemon/lib /app/lib

ENV PATH="/opt/jre/bin:${PATH}" \
    MIMIR_BIND=0.0.0.0 \
    MIMIR_PORT=47821 \
    MIMIR_VAULT=/vault

USER mimir
WORKDIR /app
VOLUME /vault
EXPOSE 47821

HEALTHCHECK --interval=30s --timeout=3s --start-period=10s \
    CMD wget -qO- "http://127.0.0.1:${MIMIR_PORT}/health" >/dev/null || exit 1

# Serial GC и лимит по памяти контейнера; CDS-архив создаётся при первом запуске и ускоряет следующие.
ENTRYPOINT ["java", "-XX:+UseSerialGC", "-XX:MaxRAMPercentage=75", "-XX:TieredStopAtLevel=1", \
            "-XX:+AutoCreateSharedArchive", "-XX:SharedArchiveFile=/tmp/mimir.jsa", \
            "-cp", "/app/lib/*", "dev.mimir.daemon.MainKt"]
