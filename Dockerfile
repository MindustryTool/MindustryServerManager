FROM gradle:8.7.0-jdk17 AS build
COPY --chown=gradle:gradle . /home/gradle/src

WORKDIR /home/gradle/src

RUN gradle clean :server:bootJar --no-daemon 

FROM eclipse-temurin:17-jre-alpine

COPY --from=build /home/gradle/src/server/src/main/resources/application.properties /app/application.properties
COPY --from=build /home/gradle/src/server/build/libs/*.jar /app/spring-boot-application.jar

# Set JVM options for minimal RAM usage
# -XX:+UseSerialGC: Low memory footprint garbage collector
# -XX:MaxRAMPercentage=75.0: Limits heap size to 75% of container memory
ENV JAVA_TOOL_OPTIONS="-XX:+UseSerialGC -XX:MaxRAMPercentage=75.0"

ENTRYPOINT ["java", "-jar", "/app/spring-boot-application.jar"]
