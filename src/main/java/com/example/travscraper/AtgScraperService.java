package com.example.travscraper;

import com.example.travscraper.entity.ScrapedHorse;
import com.example.travscraper.entity.ScrapedHorseKey;
import com.example.travscraper.repo.ScrapedHorseRepo;

import com.example.travscraper.entity.FutureHorse;
import com.example.travscraper.repo.FutureHorseRepo;

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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

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
    private final FutureHorseRepo   futureRepo;

    private Playwright    playwright;
    private Browser       browser;
    private BrowserContext ctx;

    private final ReentrantLock lock = new ReentrantLock();

    private static final DateTimeFormatter URL_DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd");


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

    /* ───────────── scheduler: historik/resultat → scraped_horse ───────────── */
    @Scheduled(cron = "0 55 23 * * *", zone = "Europe/Stockholm")
    public void scrape() {
        if (!lock.tryLock()) {
            log.warn("⏳ Previous scrape still running – skipping");
            return;
        }
        try {
            LocalDate end   = Optional.ofNullable(props.getEndDate())
                    .orElse(LocalDate.now(ZoneId.of("Europe/Stockholm"))
                            .minusDays(0));
            LocalDate start = Optional.ofNullable(props.getStartDate())
                    .orElse(end.minusDays(0));

            for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
                log.info("📆  Scraping RESULTS {}", date);
                LocalDate finalDate = date;
                props.getTracks().forEach(track -> processDateTrack(finalDate, track));
            }
        } finally {
            lock.unlock();
        }
    }

    /* ───────────── scheduler: framtid/aktuell startlista → future_horse ───────────── */
    @Scheduled(cron = "0 0 */2 * * *", zone = "Europe/Stockholm")
    public void scrapeFuture() {
        if (!lock.tryLock()) {
            log.warn("⏳ Previous scrape still running – skipping (future)");
            return;
        }
        try {
            LocalDate end   = Optional.ofNullable(props.getEndDate())
                    .orElse(LocalDate.now(ZoneId.of("Europe/Stockholm")));
            LocalDate start = Optional.ofNullable(props.getStartDate())
                    .orElse(end);

            for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
                log.info("📆  Scraping FUTURE {}", date);
                LocalDate finalDate = date;
                props.getTracks().forEach(track -> processDateTrackFuture(finalDate, track));
            }
        } finally {
            lock.unlock();
        }
    }

    private void processDateTrack(LocalDate date, String track) {

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
    tryClick();                  
    setTimeout(tryClick, 400);   
    setTimeout(tryClick, 4000);
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
                    throw e;
                }

                if ("BUTTON".equalsIgnoreCase(first.evaluate("e => e.tagName").toString())) {
                    first.click();
                    vPage.waitForSelector("tr[data-test-id^=horse-row]",
                            new Page.WaitForSelectorOptions().setTimeout(60_000));
                }

                vPage.waitForSelector(
                        "button:has-text('Tillåt alla'), button:has-text('Avvisa'), tr[data-test-id^=horse-row]",
                        new Page.WaitForSelectorOptions().setTimeout(60_000)
                );

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
                    break;
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
                try { Thread.sleep(600 + (int) (Math.random() * 1200)); } catch (InterruptedException ignored) {}
            } catch (PlaywrightException e) {
                log.warn("⚠️  Playwright-fel på {}: {}", vUrl, e.getMessage());
                if (e.getMessage().contains("Timeout")) {
                    if (++consecutiveMisses >= 2) break;
                    continue;
                }
                break;
            }
        }
    }

    /* ───────────── FUTURE: vinnare-sidor utan /resultat ───────────── */
    private void processDateTrackFuture(LocalDate date, String track) {
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
      } catch (_) {}
    };
    tryClick();
    setTimeout(tryClick, 400);
    setTimeout(tryClick, 4000);
  }
""");
        }

        int consecutiveMisses = 0;

        for (int lap = 1; lap <= 15; lap++) {

            String url = String.format(
                    "https://www.atg.se/spel/%s/vinnare/%s/lopp/%d",
                    date.format(URL_DATE_FORMAT), track, lap);

            try (Page page = ctx.newPage()) {

                Page.NavigateOptions nav = new Page.NavigateOptions()
                        .setWaitUntil(WaitUntilState.NETWORKIDLE)
                        .setTimeout(60_000);

                page.navigate(url, nav);

                if (page.url().contains("/spel/kalender/")) {
                    log.info("🔸 Lap {} not found for track {} on {}, redirected to calendar, skipping (future)", lap, track, date);
                    if (++consecutiveMisses >= 2) break;
                    continue;
                }

                try { // vänta på tabell eller cookie-knapp
                    ElementHandle first = page.waitForSelector(
                            "button:has-text(\"Tillåt alla\"):visible, " +
                                    "button:has-text(\"Avvisa\"):visible, " +
                                    "tr[data-test-id^=horse-row]",
                            new Page.WaitForSelectorOptions().setTimeout(60_000));
                    if ("BUTTON".equalsIgnoreCase(first.evaluate("e => e.tagName").toString())) {
                        first.click();
                        page.waitForSelector("tr[data-test-id^=horse-row]",
                                new Page.WaitForSelectorOptions().setTimeout(60_000));
                    }
                } catch (PlaywrightException e) {
                    if (e.getMessage().contains("Timeout")) {
                        log.info("⏩ Lap {} saknas för {} {} (future), hoppar vidare", lap, track, date);
                        if (++consecutiveMisses >= 2) break;
                        continue;
                    }
                    throw e;
                }

                if (isCancelledRace(page)) {
                    log.info("🔸 Lap {} on {} {} is cancelled (future), skipping", lap, date, track);
                    if (++consecutiveMisses >= 2) break;
                    continue;
                }

                if (!isCorrectTrack(page, track, date)) return;
                if (!isCorrectLap(page, lap, track, date)) {
                    log.info("🔸 Lap {} missing on {} {} (future), continuing", lap, date, track);
                    if (++consecutiveMisses >= 2) break;
                    continue;
                }

                consecutiveMisses = 0;

                parseAndPersistFuture(page.content(), date, track, lap);
                try { Thread.sleep(600 + (int) (Math.random() * 1200)); } catch (InterruptedException ignored) {}
            } catch (PlaywrightException e) {
                log.warn("⚠️  Playwright-fel på {}: {}", url, e.getMessage());
                if (e.getMessage().contains("Timeout")) {
                    if (++consecutiveMisses >= 2) break;
                    continue;
                }
                break;
            }
        }
    }

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

            ScrapedHorseKey key = new ScrapedHorseKey(date, bankode, String.valueOf(lap), nr);

            Optional<ScrapedHorse> existingHorseOpt = repo.findById(key);

            ScrapedHorse horse;
            if (existingHorseOpt.isPresent()) {
                horse = existingHorseOpt.get();

                horse.setNameOfHorse(name);
                horse.setPlacement(place.text().trim());
                horse.setVOdds(vOdd.text().trim());
                horse.setPOdds(pMap.getOrDefault(nr, ""));
                horse.setTrioOdds(trioMap.getOrDefault(nr, ""));
            } else {

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

    /* FUTURE: parse + spara i future_horse */
    private void parseAndPersistFuture(String html, LocalDate date, String track, int lap) {
        Elements rows = Jsoup.parse(html).select("tr[data-test-id^=horse-row]");
        if (rows.isEmpty()) return;

        List<FutureHorse> toSave = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (Element tr : rows) {
            // number_of_horse – robust: knapp-attribut → split-export → text
            String nr = extractStartNumber(tr);
            if (nr.isBlank()) {
                log.debug("⏭️  Skipping row without start number {} {} lap {}", date, track, lap);
                continue;
            }
            if (!seen.add(nr)) {
                log.debug("⏭️  Duplicate start number {} on {} {} lap {}, skipping", nr, date, track, lap);
                continue;
            }

            // name_of_horse – ta bort ledande startnummer om vi läser från split-export
            Element split = tr.selectFirst("[startlist-export-id^=startlist-cell-horse-split-export]");
            String name;
            if (split != null) {
                String[] parts = split.text().trim().split("\\s+", 2);
                name = (parts.length > 1) ? parts[1] : (parts.length > 0 ? parts[0] : "");
            } else {
                name = tr.text();
            }

            // v_odds
            Element vOddEl = tr.selectFirst("[data-test-id=startlist-cell-vodds]");
            String vOdds = vOddEl != null ? vOddEl.text().trim() : "";

            String bankode = FULLNAME_TO_BANKODE.getOrDefault(slugify(track), track);

            Optional<FutureHorse> existing = futureRepo
                    .findByDateAndTrackAndLapAndNumberOfHorse(
                            date, bankode, String.valueOf(lap), nr);

            FutureHorse fh;
            if (existing.isPresent()) {
                fh = existing.get();
                fh.setNameOfHorse(name);
                fh.setVOdds(vOdds);
            } else {
                fh = FutureHorse.builder()
                        .date(date)
                        .track(bankode)
                        .lap(String.valueOf(lap))
                        .numberOfHorse(nr)
                        .nameOfHorse(name)
                        .vOdds(vOdds)
                        .build();
            }

            toSave.add(fh);
        }

        try {
            futureRepo.saveAll(toSave);
        } catch (DataIntegrityViolationException dive) {
            log.warn("🔁 saveAll collided with unique constraint, retrying per row (future) on {} {} lap {}", date, track, lap);
            for (FutureHorse fh : toSave) {
                try {
                    // upsert per rad
                    Optional<FutureHorse> existing = futureRepo.findByDateAndTrackAndLapAndNumberOfHorse(
                            fh.getDate(), fh.getTrack(), fh.getLap(), fh.getNumberOfHorse());
                    if (existing.isPresent()) {
                        FutureHorse e = existing.get();
                        e.setNameOfHorse(fh.getNameOfHorse());
                        e.setVOdds(fh.getVOdds());
                        futureRepo.save(e);
                    } else {
                        futureRepo.save(fh);
                    }
                } catch (DataIntegrityViolationException ignored) {
                    log.warn("⚠️  Could not upsert {} {} lap {} no {}", fh.getDate(), fh.getTrack(), fh.getLap(), fh.getNumberOfHorse());
                }
            }
        }

        log.info("💾 (future) Saved/updated {} horses for {} {} lap {}", toSave.size(), date, track, lap);
    }

    // Robust extrahering av startnummer
    private String extractStartNumber(Element tr) {
        Element numBtn = tr.selectFirst("button[data-test-start-number]");
        if (numBtn != null) {
            String n = numBtn.attr("data-test-start-number");
            if (n != null && !n.isBlank()) return n.trim();
        }
        Element split = tr.selectFirst("[startlist-export-id^=startlist-cell-horse-split-export]");
        if (split != null) {
            String[] parts = split.text().trim().split("\\s+", 2);
            if (parts.length > 0 && !parts[0].isBlank()) return parts[0].trim();
        }
        Element nrText = tr.selectFirst("[data-test-id=horse-start-number], [class*=startNumber]");
        if (nrText != null) {
            String n = nrText.text().replaceAll("\\D+", "");
            if (!n.isBlank()) return n;
        }
        return "";
    }

    private static String slugify(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase();
    }

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
