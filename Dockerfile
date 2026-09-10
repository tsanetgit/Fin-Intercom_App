# Runtime-only image; build the jar outside (see README). Build for linux/amd64 when
# deploying to Azure Container Apps: docker build --platform linux/amd64 .
FROM eclipse-temurin:21-jre
# The deployable is connect-gateway's exec jar: the SDK gateway lives only in that
# module (runtime-core must never import com.tsanet.*, D6), so an image built from
# runtime-core's jar cannot start in sdk mode and silently runs fixture gateways in
# fake mode (#21).
COPY connect-gateway/target/connect-gateway-*-exec.jar /app.jar
# A filename is not evidence (scripts/run-local.sh makes the identical check at
# start-up). Since the Fin swap (#42) the engine rides as a nested artifact, so
# the marker is the outer jar's own entry name BOOT-INF/lib/gateway-engine-<v>.jar:
# zip central-directory entry names are plain bytes by the zip spec, so a binary
# grep on the outer jar reads exactly what `unzip -l` would list. (The previous
# marker, the SDK gateway class name, only matched because Spring Boot stores
# nested jars uncompressed; it was inside the nested jar, not an entry here, #83.)
# This JRE image has no unzip or jar tool; the strict listing check on the same
# marker runs host-side in image.yml before the build. -a forces text treatment
# across grep variants.
RUN grep -qa "BOOT-INF/lib/gateway-engine-" /app.jar \
    || (echo "app.jar carries no gateway-engine artifact — wrong jar copied (#21), or a pre-swap build" && exit 1)
# Every safety guard in this repo is conditioned on the prod profile: both fake
# gateways refuse to start, the in-memory link store refuses, a relative sqlite path
# refuses. Without it the image comes up healthy on fixtures and a volatile link
# store — the failure that orphaned four live BETA cases on 2026-08-07.
ENV SPRING_PROFILES_ACTIVE=prod
RUN useradd --system --uid 10001 --no-create-home connector
USER connector
# The jar's own default (application.yml server.port) — nothing here overrides it.
# run-local.sh passes --server.port=8791 for the local tunnel; a deployment wanting a
# different port sets SERVER_PORT, and EXPOSE must not promise a port the process
# does not open.
EXPOSE 8792
ENTRYPOINT ["java", "-jar", "/app.jar"]
