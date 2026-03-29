FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

COPY gradlew build.gradle.kts settings.gradle.kts /workspace/
COPY gradle /workspace/gradle

RUN chmod +x gradlew && ./gradlew --no-daemon dependencies

COPY . /workspace/

RUN chmod +x gradlew && ./gradlew --no-daemon bootJar

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S appgroup && adduser -S appuser -G appgroup

COPY --from=build /workspace/build/libs/*.jar /app/app.jar

RUN chown appuser:appgroup /app/app.jar

USER appuser

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
