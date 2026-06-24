FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace

COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src
RUN mvn -B clean package -DskipTests

FROM eclipse-temurin:21-jre-jammy

ARG APP_VERSION=1.0.0
ENV APP_HOME=/app \
    JAVA_OPTS="-XX:MaxRAMPercentage=50 -XX:+UseSerialGC -Xss256k -XX:TieredStopAtLevel=1 -XX:+ExitOnOutOfMemoryError" \
    SPRING_PROFILES_ACTIVE=prod \
    SERVER_PORT=8080 \
    LOG_PATH=/app/logs

RUN groupadd --system smsreader \
    && useradd --system --gid smsreader --home-dir /app --shell /usr/sbin/nologin smsreader \
    && apt-get update \
    && apt-get install -y --no-install-recommends wget \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /app/logs \
    && chown -R smsreader:smsreader /app

WORKDIR /app
COPY --from=build /workspace/target/sms-serial-reader-${APP_VERSION}.jar /app/app.jar

USER smsreader
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
