# syntax=docker/dockerfile:1.7
# BuildKit cache avoids re-downloading Maven dependencies on repeat builds.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /work

# Fast default for Windows developers in China; overridable if this mirror is unreachable.
ARG MAVEN_MIRROR_URL=https://maven.aliyun.com/repository/public
ENV MAVEN_MIRROR_URL=${MAVEN_MIRROR_URL}

COPY ops/maven-settings.xml /tmp/maven-settings.xml
COPY pom.xml ./
COPY src ./src

# Single Maven invocation instead of a slow, redundant dependency:go-offline pass.
# BuildKit stores ~/.m2/repository in a persistent cache between Docker builds.
RUN --mount=type=cache,id=expensepilot-maven-cache,target=/root/.m2/repository,sharing=locked \
    mvn -B -ntp -s /tmp/maven-settings.xml -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /work/target/expensepilot-java-0.2.0-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]
