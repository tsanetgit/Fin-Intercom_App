# Runtime-only image; build the jar outside (see README). Build for linux/amd64 when
# deploying to Azure Container Apps: docker build --platform linux/amd64 .
FROM eclipse-temurin:21-jre
COPY runtime-core/target/runtime-core-*-exec.jar /app.jar
EXPOSE 8792
ENTRYPOINT ["java", "-jar", "/app.jar"]
