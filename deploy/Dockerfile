FROM eclipse-temurin:11-jre-jammy

WORKDIR /app

COPY sms-serial-reader.jar /app/sms-serial-reader.jar

ENTRYPOINT ["/bin/sh", "-c", "exec java $JAVA_OPTS -jar /app/sms-serial-reader.jar"]
