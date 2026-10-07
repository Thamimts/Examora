# Build the Spring Boot application with Java 21.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml ./
COPY src/main ./src/main
RUN mvn -B -DskipTests package

# Run with a Java 21 JRE. Render injects PORT at runtime; Spring reads it.
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN groupadd --system examora && useradd --system --gid examora --home-dir /app examora
COPY --from=build --chown=examora:examora /workspace/target/examora-0.0.1-SNAPSHOT.jar /app/app.jar
USER examora
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
