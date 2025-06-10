# ---------- bygg-steg ----------
FROM maven:3.9.7-eclipse-temurin-17 AS build

WORKDIR /build

# Kopiera in källkoden
COPY pom.xml .
COPY src ./src

# Sätt UTF-8-locale så även resurser läses korrekt
ENV LANG=C.UTF-8 LC_ALL=C.UTF-8

# Bygg fet-jar (skippar tester) + tvinga UTF-8-encoding
RUN mvn -B -ntp \
    -Dfile.encoding=UTF-8 \
    clean package -DskipTests

# ---------- runtime-steg ----------
# Basbilden har redan Chromium, Firefox & WebKit + alla OS-deps
FROM mcr.microsoft.com/playwright/java:v1.43.0-jammy

# Playwright letar här efter browsers (finns redan i denna bild)
ENV PLAYWRIGHT_BROWSERS_PATH=/ms-playwright

# Render sätter $PORT – default 8080 om den saknas
ENV PORT=8080

# Samma UTF-8-locale på runtime
ENV LANG=C.UTF-8 LC_ALL=C.UTF-8

WORKDIR /app

# Kopiera färdigbyggt jar-paket från byggsteget
COPY --from=build /build/target/*-SNAPSHOT.jar app.jar

EXPOSE 8080

# Startkommandot: respektera $PORT
CMD ["sh","-c","java -jar /app/app.jar --server.port=${PORT}"]
