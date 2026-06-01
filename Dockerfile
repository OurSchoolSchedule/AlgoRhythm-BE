# Build Stage
FROM gradle:8.7-jdk17 AS builder

WORKDIR /app

COPY . .

RUN gradle build -x test

# Run Stage
FROM eclipse-temurin:17

WORKDIR /app

COPY --from=builder /app/build/libs/*.jar app.jar

ENTRYPOINT ["java", "-jar", "app.jar"]
