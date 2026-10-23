# syntax=docker/dockerfile:1

# ---- build: Gradle-сборка демона с web UI ----
FROM eclipse-temurin:27-jdk AS build
WORKDIR /src
RUN apt-get update && apt-get install -y --no-install-recommends libatomic1 && rm -rf /var/lib/apt/lists/*

# glibc-образ (не alpine): Kotlin/JS скачивает Node.js для linux-x64 (glibc) при сборке wasm web UI.
# Android SDK в образе нет: Android-таргеты пропускаются, секреты не нужны.
# Все модули копируются целиком: settings.gradle.kts подключает их все, и Gradle требует их каталоги.
COPY . .

RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew :daemon:installDist --no-daemon --console=plain

# ---- final ----
FROM eclipse-temurin:27-jre
RUN groupadd --system mimir && useradd --system --gid mimir --home-dir /app mimir \
    && mkdir /vault /app && chown mimir:mimir /vault
COPY --from=build /src/daemon/build/install/daemon/lib /app/lib

ENV MIMIR_BIND=0.0.0.0 \
    MIMIR_PORT=47821 \
    MIMIR_VAULT=/vault

USER mimir
WORKDIR /app
VOLUME /vault
EXPOSE 47821

# В образе нет curl/wget: проверяем /health через bash /dev/tcp.
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s \
    CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/${MIMIR_PORT} && printf 'GET /health HTTP/1.0\\r\\n\\r\\n' >&3 && grep -q '\"status\":\"ok\"' <&3"]

# Serial GC и лимит по памяти контейнера; CDS-архив создаётся при первом запуске и ускоряет следующие.
ENTRYPOINT ["java", "-XX:+UseSerialGC", "-XX:MaxRAMPercentage=75", "-XX:TieredStopAtLevel=1", \
            "-XX:+AutoCreateSharedArchive", "-XX:SharedArchiveFile=/tmp/mimir.jsa", \
            "-cp", "/app/lib/*", "dev.mimir.daemon.MainKt"]
