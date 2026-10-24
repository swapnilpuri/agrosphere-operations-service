FROM maven:3.9.12-eclipse-temurin-25 AS build
WORKDIR /build
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY src src
RUN mvn --batch-mode --no-transfer-progress verify
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /build/target/agrosphere-operations-service-0.2.0.jar app.jar
USER 10001:10001
EXPOSE 8083
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
