TravScraper – arkitektur och teknisk översikt 🐎

Syftet: en stabil, låg-friktion scraper som hämtar travdata från atg.se och lagrar den i PostgreSQL. Fokus ligger på robusthet mot DOM-ändringar, idempotens i skrivningar och rimlig nätetikett.


*Arkitektur i korthet*

Process: Spring Boot-applikation (Java 17) som kör två jobb:

Resultat: läser avslutade lopp (placering, v-odds, p-odds, trio-odds, tvilling-odds).

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

Schemaläggning sker externt via GitHub Actions, som startar en temporär Fly Machine.

ApplicationRunner kör en explicit one-off scraper mode med `--scraper.job=daily`.

Litet randomized sleep mellan lopp för bättre “hövlighet” mot sajten.

Spring Boot Starter (core), Data JPA, WebFlux (framtidssäkring för ev. API/feeds).

Playwright, Jsoup, Lombok.

*Fly.io one-shot Machine*

The scraper is configured as a temporary Fly Machine job, not a permanent web app.

The Java command is:

```bash
java -jar app.jar --scraper.job=daily --spring.main.web-application-type=none
```

The `daily` job runs the scraper once and exits. If a top-level scraper step throws, the process exits non-zero. Spring Boot is also configured with `spring.main.web-application-type=none`, and the app does not enable an internal scheduler.

The workflow uses:

```bash
docker.io/simnordlund/travscraper:latest
```

If your Docker Hub namespace differs, update `SCRAPER_IMAGE` in `.github/workflows/run-scraper.yml` and use the same image name in the build/push commands.

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

Store database/API credentials as Fly app secrets. The GitHub Action only needs `FLY_API_TOKEN`.

Rebuild and push the Docker image after code changes:

```bash
docker build -t docker.io/simnordlund/travscraper:latest .
docker push docker.io/simnordlund/travscraper:latest
```

Manual Fly test:

```bash
run_output="$(flyctl machine run docker.io/simnordlund/travscraper:latest \
  --app travscraper \
  --region arn \
  --rm \
  --restart no \
  --vm-memory 2048 \
  --vm-cpus 2 \
  --detach \
  java -jar app.jar --scraper.job=daily --spring.main.web-application-type=none)"

echo "$run_output"

machine_id="$(echo "$run_output" | awk '/Machine ID:/ {print $3; exit}')"
flyctl machine wait "$machine_id" --app travscraper --state started --wait-timeout 5m
```

The workflow uses `--detach` plus `machine wait` because Fly can take more than 60 seconds to pull/start a large Docker image. Without that, GitHub Actions can show a false failure even though the Fly Machine actually starts. The Machine still uses `--rm` and `--restart no`, so Fly deletes it after the scraper exits and will not restart it.
