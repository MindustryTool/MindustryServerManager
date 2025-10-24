FROM gradle:8.9.0-jdk22 AS build
COPY --chown=gradle:gradle . /home/gradle/src

WORKDIR /home/gradle/src

RUN gradle clean :server:bootJar --no-daemon 

FROM eclipse-temurin:22-jre-alpine

COPY --from=build /home/gradle/src/server/src/main/resources/application.properties /app/application.properties
COPY --from=build /home/gradle/src/server/build/libs/*.jar /app/spring-boot-application.jar

ENTRYPOINT ["java","-jar", "/app/spring-boot-application.jar"]


