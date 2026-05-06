FROM gradle:8.7.0-jdk17 AS build
WORKDIR /home/gradle/src

COPY --chown=gradle:gradle gradle/ /home/gradle/src/gradle/
COPY --chown=gradle:gradle gradlew gradlew.bat settings.gradle build.gradle gradle.properties /home/gradle/src/
COPY --chown=gradle:gradle dto/build.gradle /home/gradle/src/dto/build.gradle
COPY --chown=gradle:gradle plugin/build.gradle plugin/gradle.properties /home/gradle/src/plugin/
COPY --chown=gradle:gradle server/build.gradle /home/gradle/src/server/build.gradle

RUN gradle :server:dependencies --no-daemon

COPY --chown=gradle:gradle . /home/gradle/src

RUN gradle :server:shadowJar --no-daemon

FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

COPY --from=build /home/gradle/src/server/build/libs/application.jar /app/application.jar

# Set JVM options for minimal RAM usage
# -XX:+UseSerialGC: Low memory footprint garbage collector
# -XX:MaxRAMPercentage=75.0: Limits heap size to 75% of container memory
ENV JAVA_TOOL_OPTIONS="-XX:+UseSerialGC -XX:MaxRAMPercentage=75.0"

ENTRYPOINT ["java", "-jar", "/app/application.jar"]
