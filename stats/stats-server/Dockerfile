FROM eclipse-temurin:21-jre-jammy

WORKDIR /app
COPY target/*.jar app.jar

USER 10001:10001
EXPOSE 9090
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
