FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src src
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:21.0.12_8-jre
RUN useradd --uid 10001 --no-create-home --shell /usr/sbin/nologin nexus
WORKDIR /app
COPY --from=build /src/target/nexus-api-*.jar app.jar
USER 10001
EXPOSE 8080 8081
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=60", "-jar", "/app/app.jar"]
