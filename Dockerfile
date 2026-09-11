#BUILD
FROM maven:3.9.16-eclipse-temurin-25 AS build

ARG GITHUB_ACTOR
ARG FLEXI_COMMON_TOKEN

ENV GITHUB_ACTOR=$GITHUB_ACTOR
ENV FLEXI_COMMON_TOKEN=$FLEXI_COMMON_TOKEN

WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN --mount=type=secret,id=maven_settings,target=/root/.m2/settings.xml \
    mvn clean package -DskipTests

#RUN
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
ENTRYPOINT ["java", "-jar", "-Dspring.profiles.active=docker", "app.jar"]