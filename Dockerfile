FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline

COPY src/ src/
RUN ./mvnw -B -q -DskipTests package

FROM mcr.microsoft.com/openjdk/jdk:21-ubuntu
WORKDIR /app

RUN groupadd --system tokenflow && useradd --system --gid tokenflow --home-dir /app tokenflow
COPY --from=build --chown=tokenflow:tokenflow /workspace/target/token-design-patterns-0.0.1-SNAPSHOT.jar app.jar

USER tokenflow
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -Djava.security.egd=file:/dev/./urandom"

ENTRYPOINT ["java", "-jar", "/app/app.jar"]