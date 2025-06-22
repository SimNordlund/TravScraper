package com.example.travscraper;

import com.example.travscraper.entity.ScrapedHorse;
import com.example.travscraper.entity.ScrapedHorseKey;
import com.example.travscraper.repo.ScrapedHorseRepo;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.WaitForSelectorState;
import com.microsoft.playwright.options.WaitUntilState;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Service
@RequiredArgsConstructor
public class AtgScraperService {

    private final ScraperProperties props;
    private final ScrapedHorseRepo  repo;

    private Playwright    playwright;
    private Browser       browser;
    private BrowserContext ctx;                   //  ⇦  persistent context (keeps cookies)

    private final ReentrantLock lock = new ReentrantLock();

    private static final DateTimeFormatter URL_DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /* ───────────── lifecycle ───────────── */
    @PostConstruct
    void initBrowser() {
        playwright = Playwright.create();
        browser = playwright.chromium()
                .launch(new BrowserType.LaunchOptions()
                        .setHeadless(true));
        log.info("🖥️  Headless browser launched");
    }

    @PreDestroy
    void closeBrowser() {
        if (ctx        != null) ctx.close();
        if (browser    != null) browser.close();
        if (playwright != null) playwright.close();
    }

    /* ───────────── scheduler ───────────── */
    @Scheduled(cron = "0 0 * * * *", zone = "Europe/Stockholm")
    public void scrape() {
        if (!lock.tryLock()) {
            log.warn("⏳ Previous scrape still running – skipping");
            return;
        }
        try {
            LocalDate target = LocalDate.now(ZoneId.of("Europe/Stockholm")).minusDays(1);
            log.info("📆  Scraping {}", target);
            props.getTracks().forEach(t -> processDateTrack(target, t));
        } finally {
            lock.unlock();
        }
    }

    private void processDateTrack(LocalDate date, String track) {

        /* ── create context once per job (keeps consent-cookie) ── */
        if (ctx == null) {
            ctx = browser.newContext(
                    new Browser.NewContextOptions()
                            .setLocale("sv-SE")
                            .setTimezoneId("Europe/Stockholm")
                            .setGeolocation(59.33, 18.06)
                            .setPermissions(List.of("geolocation"))
                            .setUserAgent(
                                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                                            "AppleWebKit/537.36 (KHTML, like Gecko) " +
                                            "Chrome/125.0.0.0 Safari/537.36")
            );

// runs on every navigation
            ctx.addInitScript("""
  () => {
    const tryClick = () => {
      const patterns = [
        /Jag\\s*f(ö|o)rst(å|a)r/i,
        /Godk(ä|a)nn\\s+alla\\s+cookies/i,
        /Endast\\s+n(ö|o)dv(ä|a)ndiga/i,
        /Tillåt\\s+alla/i,
        /Avvisa/i
      ];
      try {
        const btn = [...document.querySelectorAll('button')]
          .find(b => patterns.some(rx => rx.test(b.textContent)));
        if (btn) btn.click();
      } catch (_) { /* ignore shadow-DOM issues */ }
    };
    tryClick();                  // immediately
    setTimeout(tryClick, 400);   // in case banner slides in late
    setTimeout(tryClick, 4000);  // one more try
  }
""");
        }

        int consecutiveMisses = 0;

        for (int lap = 1; lap <= 15; lap++) {

            String base = "https://www.atg.se/spel/%s/%s/%s/lopp/%d/resultat";
            String vUrl = String.format(base, date.format(URL_DATE_FORMAT), "vinnare", track, lap);
            String pUrl = String.format(base, date.format(URL_DATE_FORMAT), "plats",   track, lap);
            String tUrl = String.format(base, date.format(URL_DATE_FORMAT), "trio",    track, lap);

            try (Page vPage = ctx.newPage();
                 Page pPage = ctx.newPage();
                 Page tPage = ctx.newPage()) {

                Page.NavigateOptions nav = new Page.NavigateOptions()
                        .setWaitUntil(WaitUntilState.NETWORKIDLE)
                        .setTimeout(60_000);

                vPage.navigate(vUrl, nav);

                if (vPage.url().contains("/spel/kalender/")) {
                    log.info("🔸 Lap {} not found for track {} on {}, redirected to calendar, skipping", lap, track, date);
                    if (++consecutiveMisses >= 2) break;
                    continue;
                }

                ElementHandle first;
                try {
                    first = vPage.waitForSelector(
                            "button:has-text(\"Tillåt alla\"):visible, " +
                                    "button:has-text(\"Avvisa\"):visible, " +
                                    "tr[data-test-id^=horse-row]",
                            new Page.WaitForSelectorOptions().setTimeout(60_000));
                } catch (PlaywrightException e) {
                    if (e.getMessage().contains("Timeout")) {
                        log.info("⏩ Lap {} saknas för {} {}, hoppar vidare", lap, track, date);
                        if (++consecutiveMisses >= 2) break;
                        continue;
                    }
                    throw e; // annat fel – bubbla up
                }

// if the thing we got back is a button → click it, then wait for table
                if ("BUTTON".equalsIgnoreCase(first.evaluate("e => e.tagName").toString())) {
                    first.click();                           // dismiss banner
                    vPage.waitForSelector("tr[data-test-id^=horse-row]",   // now wait for table
                            new Page.WaitForSelectorOptions().setTimeout(60_000));
                }

                vPage.waitForSelector(
                        "button:has-text('Tillåt alla'), button:has-text('Avvisa'), tr[data-test-id^=horse-row]",
                        new Page.WaitForSelectorOptions().setTimeout(60_000)
                );

                if (System.getenv("FLY_APP_NAME") != null) {            // only in Fly
                    try {
                        vPage.screenshot(new Page.ScreenshotOptions()
                                .setPath(Paths.get("/app/debug-vpage.png"))
                                .setFullPage(true));
                        Files.writeString(Path.of("/app/debug-vpage.html"), vPage.content());
                        log.info("📸 saved /app/debug-vpage.png + .html");
                    } catch (Exception e) {
                        log.warn("debug dump failed", e);
                    }
                }

                pPage.navigate(pUrl, nav);
                tPage.navigate(tUrl, nav);

                if (isCancelledRace(vPage)) {
                    log.info("🔸 Lap {} on {} {} is cancelled, skipping", lap, date, track);
                    if (++consecutiveMisses >= 2) break;
                    continue;
                }

                try {
                    vPage.waitForSelector("tr[data-test-id^=horse-row]",
                            new Page.WaitForSelectorOptions().setTimeout(60_000));
                } catch (PlaywrightException e) {
                    log.warn("⚠️  Playwright-fel på {}: {}", vUrl, e.getMessage());
                    if (e.getMessage().contains("Timeout")) {
                        if (++consecutiveMisses >= 2) break;
                        continue;
                    }
                    break; // annat fel – ge upp banan
                }

                if (!isCorrectTrack(vPage, track, date)) return;
                if (!isCorrectLap(vPage, lap, track, date) ||
                        !isCorrectLap(pPage, lap, track, date) ||
                        !isCorrectLap(tPage, lap, track, date)) {
                    log.info("🔸 Lap {} missing on {} {}, continuing", lap, date, track);
                    if (++consecutiveMisses >= 2) break;
                    continue;
                }

                consecutiveMisses = 0;

                pPage.waitForSelector("tr[data-test-id^=horse-row]",
                        new Page.WaitForSelectorOptions().setTimeout(60_000));

                tPage.waitForSelector("text=\"Rätt kombination:\"",
                        new Page.WaitForSelectorOptions()
                                .setTimeout(60_000)
                                .setState(WaitForSelectorState.ATTACHED));

                Map<String,String> pMap   = extractOddsMap(pPage, "[data-test-id=startlist-cell-podds]");
                Map<String,String> trioMap= extractTrioMap(tPage);

                parseAndPersist(vPage.content(), date, track, lap, pMap, trioMap);
                Thread.sleep(600 + (int) (Math.random() * 1200));

            } catch (PlaywrightException e) {
                log.warn("⚠️  Playwright-fel på {}: {}", vUrl, e.getMessage());
                if (e.getMessage().contains("Timeout")) {
                    if (++consecutiveMisses >= 2) break;
                    continue;
                }
                break; // annat fel – ge upp banan
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /* ───────────── helpers (unchanged) ───────────── */
    private boolean isCancelledRace(Page page) {
        return Jsoup.parse(page.content())
                .selectFirst("span[class*=cancelledRace], span:matchesOwn(Inställt\\,?\\s+insatser)") != null;
    }

    private boolean isCorrectTrack(Page page, String expected, LocalDate date) {
        Element active = Jsoup.parse(page.content())
                .selectFirst("span[data-test-id^=calendar-menu-track-][data-test-active=true]");
        if (active == null) return true;
        String slug = slugify(active.attr("data-test-id").substring("calendar-menu-track-".length()));
        if (!slug.equals(expected.toLowerCase())) {
            log.info("↪️  {} not present on {} (page shows {}), skipping whole day/track", expected, date, slug);
            return false;
        }
        return true;
    }

    private boolean isCorrectLap(Page page, int expected, String track, LocalDate date) {
        Document doc = Jsoup.parse(page.content());
        Element sel  = doc.selectFirst("[data-test-selected=true]");
        if (sel == null) return true;
        String current = sel.text().trim();
        if (!current.equals(String.valueOf(expected))) {
            log.info("↪️  lap {} not present on {} {} (page shows {}), skipping rest of laps", expected, date, track, current);
            return false;
        }
        return true;
    }

    private Map<String,String> extractOddsMap(Page page, String oddsSelector) {
        Map<String,String> map = new HashMap<>();
        for (Element tr : Jsoup.parse(page.content()).select("tr[data-test-id^=horse-row]")) {
            Element split = tr.selectFirst("[startlist-export-id^=startlist-cell-horse-split-export]");
            Element odds  = tr.selectFirst(oddsSelector);
            if (split == null || odds == null) continue;
            String nr = split.text().trim().split("\\s+",2)[0];
            map.put(nr, odds.text().trim());
        }
        return map;
    }

    private Map<String,String> extractTrioMap(Page page) {
        Map<String,String> map = new HashMap<>();
        Document doc = Jsoup.parse(page.content());

        Element comboLabel = doc.selectFirst("span:matchesOwn(^\\s*Rätt\\skombination:?)");
        Element oddsLabel  = doc.selectFirst("span:matchesOwn(^\\s*Odds:?)");
        if (comboLabel == null || oddsLabel == null) return map;

        String combo = comboLabel.parent().selectFirst("span[class*=\"--value\"]").text().trim();
        String odds  = oddsLabel .parent().selectFirst("span[class*=\"--value\"]").text().trim();
        Arrays.stream(combo.split("-")).forEach(n -> map.put(n, odds));
        return map;
    }

    private void parseAndPersist(String html, LocalDate date, String track, int lap,
                                 Map<String, String> pMap, Map<String, String> trioMap) {

        Elements rows = Jsoup.parse(html).select("tr[data-test-id^=horse-row]");
        if (rows.isEmpty()) return;

        List<ScrapedHorse> horsesToSave = new ArrayList<>();

        for (Element tr : rows) {
            Element place = tr.selectFirst("[data-test-id=horse-placement]");
            Element split = tr.selectFirst("[startlist-export-id^=startlist-cell-horse-split-export]");
            Element vOdd  = tr.selectFirst("[data-test-id=startlist-cell-vodds]");
            if (place == null || split == null || vOdd == null) continue;

            String[] parts = split.text().trim().split("\\s+", 2);
            String nr   = parts.length > 0 ? parts[0] : "";
            String name = parts.length > 1 ? parts[1] : "";

            String bankode = FULLNAME_TO_BANKODE.getOrDefault(slugify(track), track);

            // Construct composite key
            ScrapedHorseKey key = new ScrapedHorseKey(date, bankode, String.valueOf(lap), nr);

            // Fetch existing entry if any
            Optional<ScrapedHorse> existingHorseOpt = repo.findById(key);

            ScrapedHorse horse;
            if (existingHorseOpt.isPresent()) {
                horse = existingHorseOpt.get();
                // Update existing record
                horse.setNameOfHorse(name);
                horse.setPlacement(place.text().trim());
                horse.setVOdds(vOdd.text().trim());
                horse.setPOdds(pMap.getOrDefault(nr, ""));
                horse.setTrioOdds(trioMap.getOrDefault(nr, ""));
            } else {
                // Insert new record
                horse = ScrapedHorse.builder()
                        .date(date).track(bankode).lap(String.valueOf(lap))
                        .numberOfHorse(nr).nameOfHorse(name).placement(place.text().trim())
                        .vOdds(vOdd.text().trim())
                        .pOdds(pMap.getOrDefault(nr, ""))
                        .trioOdds(trioMap.getOrDefault(nr, ""))
                        .build();
            }

            horsesToSave.add(horse);
        }

        repo.saveAll(horsesToSave);
        log.info("💾 Saved {} horses for {} {} lap {}", horsesToSave.size(), date, track, lap);
    }

    private static String slugify(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase();
    }

    /*  bankod-tabellen oförändrad  */
    private static final Map<String, String> FULLNAME_TO_BANKODE = Map.ofEntries(
            Map.entry("arvika", "Ar"), Map.entry("axevalla", "Ax"),
            Map.entry("bergsaker", "B"), Map.entry("boden", "Bo"),
            Map.entry("bollnas", "Bs"), Map.entry("dannero", "D"),
            Map.entry("dala jarna", "Dj"), Map.entry("eskilstuna", "E"),
            Map.entry("jagersro", "J"), Map.entry("farjestad", "F"),
            Map.entry("gavle", "G"), Map.entry("goteborg trav", "Gt"),
            Map.entry("hagmyren", "H"), Map.entry("halmstad", "Hd"),
            Map.entry("hoting", "Hg"), Map.entry("karlshamn", "Kh"),
            Map.entry("kalmar", "Kr"), Map.entry("lindesberg", "L"),
            Map.entry("lycksele", "Ly"), Map.entry("mantorp", "Mp"),
            Map.entry("oviken", "Ov"), Map.entry("romme", "Ro"),
            Map.entry("rattvik", "Rä"), Map.entry("solvalla", "S"),
            Map.entry("skelleftea", "Sk"), Map.entry("solanget", "Sä"),
            Map.entry("tingsryd", "Ti"), Map.entry("taby trav", "Tt"),
            Map.entry("umaker", "U"), Map.entry("vemdalen", "Vd"),
            Map.entry("vaggeryd", "Vg"), Map.entry("visby", "Vi"),
            Map.entry("aby", "Å"), Map.entry("amal", "Åm"),
            Map.entry("arjang", "År"), Map.entry("orebro", "Ö"),
            Map.entry("ostersund", "Ös")
    );
}