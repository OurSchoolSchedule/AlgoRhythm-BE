FROM gradle:8.7-jdk17 AS build
WORKDIR /app
COPY . .
RUN gradle --no-daemon bootJar

FROM eclipse-temurin:17-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends redis-server && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
COPY demo/seed.sql demo/local.yml ./demo/
ADD --checksum=sha256:23fb9a6526ba77dd7e240e040a7de8d00ff1089851bb145da1247d91fba660e5 https://raw.githubusercontent.com/OurSchoolSchedule/AlgoRhythm/2699068325e88fc85981fe76b2da692ee8260868/frontend/src/test/fixtures/school42/school_seed.sql ./demo/school42.sql
RUN chmod 444 ./demo/school42.sql
COPY demo/fe20-preview.sh ./demo/
USER nobody
ENTRYPOINT ["sh", "demo/fe20-preview.sh"]
