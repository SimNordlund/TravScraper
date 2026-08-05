TravScraper – arkitektur och teknisk översikt 🐎

Syftet: en stabil, låg-friktion scraper som hämtar travdata från atg.se och lagrar den i PostgreSQL. Fokus ligger på robusthet mot DOM-ändringar, idempotens i skrivningar och rimlig nätetikett.


*Arkitektur i korthet*

Process: Spring Boot-applikation (Java 17) som kör två jobb:

Resultat: läser avslutade lopp (placering, v-odds, p-odds, trio-odds).

Framtida startlistor: läser kommande lopp (startnr, hästnamn, v-odds).

Webbautomation: Playwright (Chromium, headless) med svensk locale, Europe/Stockholm-zon, lätt geolocation och egen user-agent. Cookie-banners klickas bort via ett litet init-script.

HTML-tolkning: Jsoup med uttryckliga CSS-selektorer för tabellrader och odds-celler. Extra kontroller för att undvika fel sida/fel lopp (t ex redirect till kalender).

Persistens: Spring Data JPA mot PostgreSQL. Resultat skrivs med kompositnyckel; framtida startlistor har auto-ID + unik constraint och egen upsert-strategi vid kollisioner.

Idempotens & låsning: ReentrantLock säkerställer att schemalagda jobb inte kör parallellt. Databasens constraints förhindrar dubletter.

Konfiguration: starkt typade @ConfigurationProperties (banor, datumintervall). Tidszon och locale är satta i Playwright-context.

*Tekniska val*

Java 17 (records, moderna språkfeatures; ScrapedHorseKey är ett record).

Spring Boot 3.4.x (AOT-redo, uppdaterad scheduler och JPA-stack).

Playwright for Java 1.43:

Headless Chromium, WaitUntilState.NETWORKIDLE, explicita timeouts.

Init-script för att klicka bort vanliga cookie-knappar (regex på svensk text).

Kontext återanvänds; nya sidor per mål-URL (vinnare/plats/trio).

Jsoup 1.19:

Selektorer för hästrader (tr[data-test-id^=horse-row]), odds-kolumner och statusfält.

DOM-invarians: robust extrahering av startnummer (knapp-attribut → split-export → text).

Spring Data JPA mot PostgreSQL (drivrutin 42.7).

scraped_horse: kompositnyckel (date, track, lap, number_of_horse) via @IdClass → naturlig idempotens.

future_horse: bigserial PK + unik constraint på (date, track, lap, number_of_horse). Vid DataIntegrityViolationException görs per-rad upsert.

Bankod-mappning (FULLNAME_TO_BANKODE) normaliserar URL-slugs till interna travkoder (t ex solvalla → S, åby → Å).

Två @Scheduled-jobb på 23:55 Europe/Stockholm.

CommandLineRunner kan trigga scraping vid uppstart (enkelt att toggla).

Litet randomized sleep mellan lopp för bättre “hövlighet” mot sajten.

Spring Boot Starter (core), Data JPA, WebFlux (framtidssäkring för ev. API/feeds).

Playwright, Jsoup, Lombok.

*Fly.io one-shot Machine*

The scraper is configured as a temporary Fly Machine job. The Docker image runs:

```bash
java -jar /app/app.jar
```

Spring Boot is configured with `spring.main.web-application-type=none`, so the scraper does not start a permanent web server. The application exits after the `CommandLineRunner` completes, and the GitHub workflow starts it with `--rm` and `--restart no` so Fly deletes the Machine after completion and does not restart it.

The workflow uses:

```bash
docker.io/simnordlund/travscraper:latest
```

If your Docker Hub namespace differs, update `SCRAPER_IMAGE` in `.github/workflows/run-scraper.yml`.

Initial Fly setup:

```bash
fly apps create travscraper
fly secrets set DATABASE_URL='jdbc:postgresql://...' DATABASE_USERNAME='...' DATABASE_PASSWORD='...' --app travscraper
fly tokens create deploy -a travscraper
```

Set this GitHub repository secret from the token output:

```text
FLY_API_TOKEN
```

Keep database/API credentials as Fly app secrets. The GitHub Action only needs `FLY_API_TOKEN`.

Rebuild and push the Docker image after code changes:

```bash
docker build -t docker.io/simnordlund/travscraper:latest .
docker push docker.io/simnordlund/travscraper:latest
```
