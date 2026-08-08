# Runtime-only image; build the jar outside (see README). Build for linux/amd64 when
# deploying to Azure Container Apps: docker build --platform linux/amd64 .
FROM eclipse-temurin:21-jre
# The deployable is connect-gateway's exec jar: runtime-core carries no com.tsanet.*
# classes by design rule (D6), so an image built from ITS jar cannot start in sdk mode
# and silently runs fixture gateways in fake mode (#21).
COPY connect-gateway/target/connect-gateway-*-exec.jar /app.jar
# A filename is not evidence (scripts/run-local.sh makes the identical check at
# start-up): zip central-directory entry names are plain bytes, so a binary grep
# proves the SDK gateway is actually aboard. -a forces text treatment across grep
# variants.
RUN grep -qa "SdkTsanetGateway" /app.jar \
    || (echo "app.jar does not contain SdkTsanetGateway — wrong jar copied (#21)" && exit 1)
# Every safety guard in this repo is conditioned on the prod profile: both fake
# gateways refuse to start, the in-memory link store refuses, a relative sqlite path
# refuses. Without it the image comes up healthy on fixtures and a volatile link
# store — the failure that orphaned four live BETA cases on 2026-08-07.
ENV SPRING_PROFILES_ACTIVE=prod
RUN useradd --system --uid 10001 --no-create-home connector
USER connector
EXPOSE 8791
ENTRYPOINT ["java", "-jar", "/app.jar"]
