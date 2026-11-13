FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -q dependency:go-offline
COPY src src
RUN ./mvnw -q package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd -r -u 1001 app && mkdir -p /data/resumes && chown app /data/resumes
COPY --from=build /src/target/backend-*.jar app.jar
USER app
ENV STORAGE_DIR=/data/resumes
EXPOSE 8080
# Render free = 512 MB for the WHOLE process: heap 55% (~280 MB) + capped metaspace / code cache / thread stacks.
# SerialGC: least memory overhead on 1 CPU. TieredStopAtLevel=1: smaller code cache and a faster cold start.
ENTRYPOINT ["java", "-XX:+UseSerialGC", "-XX:MaxRAMPercentage=55", "-XX:MaxMetaspaceSize=192m", "-XX:ReservedCodeCacheSize=48m", "-XX:TieredStopAtLevel=1", "-Xss512k", "-XX:+ExitOnOutOfMemoryError", "-jar", "app.jar"]
