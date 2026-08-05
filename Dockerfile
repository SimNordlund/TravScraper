# ---------- build stage ----------
FROM maven:3.9.7-eclipse-temurin-17 AS build

WORKDIR /build

COPY pom.xml .
COPY src ./src

ENV LANG=C.UTF-8 LC_ALL=C.UTF-8

RUN mvn -B -ntp \
    -Dfile.encoding=UTF-8 \
    clean package -DskipTests

# ---------- runtime stage ----------
FROM mcr.microsoft.com/playwright/java:v1.43.0-jammy

ENV PLAYWRIGHT_BROWSERS_PATH=/ms-playwright
ENV LANG=C.UTF-8 LC_ALL=C.UTF-8

WORKDIR /app

COPY --from=build /build/target/TravScraper-0.0.1-SNAPSHOT.jar app.jar

CMD ["java", "-jar", "/app/app.jar"]
