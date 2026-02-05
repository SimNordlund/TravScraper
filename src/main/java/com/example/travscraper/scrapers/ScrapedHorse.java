package com.example.travscraper.scrapers;

import com.example.travscraper.ScraperProperties;
import com.example.travscraper.entity.ScrapedHorseKey;
import com.example.travscraper.repo.ScrapedHorseRepo;
import com.example.travscraper.repo.StartListHorseRepo;
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
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

import static com.example.travscraper.helpers.TrackHelper.FULLNAME_TO_BANKODE;

@Slf4j
@Service
@RequiredArgsConstructor
public class ScrapedHorse {

    private static final DateTimeFormatter URL_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private static final Map<String, String> BANKODE_TO_SLUG;

    static {
        Map<String, String> m = new HashMap<>();
        FULLNAME_TO_BANKODE.forEach((slug, code) -> m.put(code, slug));
        BANKODE_TO_SLUG = Collections.unmodifiableMap(m);
    }

    private final ScraperProperties props;
    private final ScrapedHorseRepo repo;
    private final StartListHorseRepo startListRepo;

    private final ReentrantLock lock = new ReentrantLock();
    private Playwright playwright;
    private Browser browser;
    private BrowserContext ctx;

    @PostConstruct
    void initBrowser() {
        playwright = Playwright.create();
        browser = playwright.chromium().launch(
                new BrowserType.LaunchOptions()
                        .setHeadless(true)
                        .setArgs(List.of("--disable-blink-features=AutomationControlled"))
        );
        log.info("🖥️  Headless browser launched (ScrapedHorse scraper)");
    }

    @PreDestroy
    void closeBrowser() {
        if (ctx != null) ctx.close();
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
    }

    // @Scheduled(cron = "0 55 23 * * *", zone = "Europe/Stockholm")
    public void scrape() {
        if (!lock.tryLock()) {
            log.warn("⏳ Previous scrape still running – skipping");
            return;
        }
        try {
            LocalDate end = Optional.ofNullable(props.getEndDateResults())
                    .orElse(LocalDate.now(ZoneId.of("Europe/Stockholm")).minusDays(0));
            LocalDate start = Optional.ofNullable(props.getStartDateResults())
                    .orElse(end.minusDays(0));

            for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
                log.info("📆  Scraping RESULTS {}", date);
                List<String> tracks = tracksFor(date);
                for (String track : tracks) {
                    processDateTrack(date, track);
                }
            }
        } finally {
            lock.unlock();
        }
    }

    private void ensureContext() {
        if (ctx != null) return;

        ctx = browser.newContext(
                new Browser.NewContextOptions()
                        .setLocale("sv-SE")
                        .setTimezoneId("Europe/Stockholm")
                        .setGeolocation(59.33, 18.06)
                        .setPermissions(List.of("geolocation"))
                        .setViewportSize(1600, 900)
                        .setUserAgent(
                                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                                        "AppleWebKit/537.36 (KHTML, like Gecko) " +
                                        "Chrome/125.0.0.0 Safari/537.36")
        );

        ctx.addInitScript("""
                  () => {
                    // försök minska anti-bot-detektering 
                    try { Object.defineProperty(navigator, 'webdriver', { get: () => undefined }); } catch(e) {}
                    try { Object.defineProperty(navigator, 'languages', { get: () => ['sv-SE','sv','en-US','en'] }); } catch(e) {}
                    try { Object.defineProperty(navigator, 'plugins', { get: () => [1,2,3,4,5] }); } catch(e) {}

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

    private List<String> tracksFor(LocalDate date) {
        int yyyymmdd = toYyyymmdd(date);
        List<String> codes = startListRepo.findDistinctBanKoderOn(yyyymmdd);
        if (codes == null || codes.isEmpty()) {
            log.info("ℹ️  Inga banor i startlista för {}", date);
            return List.of();
        }

        List<String> slugs = new ArrayList<>();
        for (String code : codes) {
            String slug = BANKODE_TO_SLUG.get(code);
            if (slug == null || slug.isBlank()) {
                log.warn("⚠️  Okänd bankod '{}' för {}, hoppar den banan", code, date);
                continue;
            }
            slugs.add(slug);
        }
        return slugs;
    }

    private void processDateTrack(LocalDate date, String track) {
        ensureContext();

        int consecutiveMisses = 0;

        for (int lap = 1; lap <= 15; lap++) {
            String base = "https://www.atg.se/spel/%s/%s/%s/lopp/%d/resultat";
            String vUrl = String.format(base, date.format(URL_DATE_FORMAT), "vinnare", track, lap);
            String pUrl = String.format(base, date.format(URL_DATE_FORMAT), "plats", track, lap);
            String tUrl = String.format(base, date.format(URL_DATE_FORMAT), "trio", track, lap);

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
                    if (e.getMessage() != null && e.getMessage().contains("Timeout")) {
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
                        "button:has-text(\"Tillåt alla\"), button:has-text(\"Avvisa\"), tr[data-test-id^=horse-row]",
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
                    if (e.getMessage() != null && e.getMessage().contains("Timeout")) {
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

                Map<String, String> pMap = extractOddsMap(pPage, "[data-test-id=startlist-cell-podds]");
                Map<String, String> trioMap = extractTrioMap(tPage);

                parseAndPersist(vPage.content(), date, track, lap, pMap, trioMap);

                try {
                    Thread.sleep(600 + (int) (Math.random() * 1200));
                } catch (InterruptedException ignored) {
                }
            } catch (PlaywrightException e) {
                log.warn("⚠️  Playwright-fel på {}: {}", vUrl, e.getMessage());
                if (e.getMessage() != null && e.getMessage().contains("Timeout")) {
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

        String slug = trackKey(active.attr("data-test-id").substring("calendar-menu-track-".length()));
        if (!slug.equals(trackKey(expected))) {
            log.info("↪️  {} not present on {} (page shows {}), skipping whole day/track", expected, date, slug);
            return false;
        }
        return true;
    }

    private boolean isCorrectLap(Page page, int expected, String track, LocalDate date) {
        Document doc = Jsoup.parse(page.content());
        Element sel = doc.selectFirst("[data-test-selected=true]");
        if (sel == null) return true;

        String current = sel.text().trim();
        if (!current.equals(String.valueOf(expected))) {
            log.info("↪️  lap {} not present on {} {} (page shows {}), skipping rest of laps", expected, date, track, current);
            return false;
        }
        return true;
    }

    private Map<String, String> extractOddsMap(Page page, String oddsSelector) {
        Map<String, String> map = new HashMap<>();
        for (Element tr : Jsoup.parse(page.content()).select("tr[data-test-id^=horse-row]")) {
            Element split = tr.selectFirst("[startlist-export-id^=startlist-cell-horse-split-export]");
            Element odds = tr.selectFirst(oddsSelector);
            if (split == null || odds == null) continue;
            String nr = split.text().trim().split("\\s+", 2)[0];
            map.put(nr, odds.text().trim());
        }
        return map;
    }

    private Map<String, String> extractTrioMap(Page page) {
        Map<String, String> map = new HashMap<>();
        Document doc = Jsoup.parse(page.content());

        Element comboLabel = doc.selectFirst("span:matchesOwn(^\\s*Rätt\\skombination:?)");
        Element oddsLabel = doc.selectFirst("span:matchesOwn(^\\s*Odds:?)");
        if (comboLabel == null || oddsLabel == null) return map;

        Element comboValue = comboLabel.parent().selectFirst("span[class*=\"--value\"]");
        Element oddsValue = oddsLabel.parent().selectFirst("span[class*=\"--value\"]");
        if (comboValue == null || oddsValue == null) return map;

        String combo = comboValue.text().trim();
        String odds = oddsValue.text().trim();
        Arrays.stream(combo.split("-")).forEach(n -> map.put(n, odds));
        return map;
    }

    private void parseAndPersist(
            String html,
            LocalDate date,
            String track,
            int lap,
            Map<String, String> pMap,
            Map<String, String> trioMap
    ) {
        Elements rows = Jsoup.parse(html).select("tr[data-test-id^=horse-row]");
        if (rows.isEmpty()) return;

        List<com.example.travscraper.entity.ScrapedHorse> horsesToSave = new ArrayList<>();

        for (Element tr : rows) {
            Element place = tr.selectFirst("[data-test-id=horse-placement]");
            Element split = tr.selectFirst("[startlist-export-id^=startlist-cell-horse-split-export]");
            Element vOdd = tr.selectFirst("[data-test-id=startlist-cell-vodds]");
            if (place == null || split == null || vOdd == null) continue;

            String[] parts = split.text().trim().split("\\s+", 2);
            String nr = parts.length > 0 ? parts[0] : "";
            String name = parts.length > 1 ? parts[1] : "";

            String normalizedName = normalizeHorseNameSimple(name);

            String bankode = toKnownBankodOrNull(track);
            if (bankode == null) {
                log.warn("⚠️  Okänd bana '{}' -> skippar RESULTS (ScrapedHorse) helt", track);
                continue;
            }

            ScrapedHorseKey key = new ScrapedHorseKey(date, bankode, String.valueOf(lap), nr);
            Optional<com.example.travscraper.entity.ScrapedHorse> existingHorseOpt = repo.findById(key);

            com.example.travscraper.entity.ScrapedHorse horse;
            if (existingHorseOpt.isPresent()) {
                horse = existingHorseOpt.get();
                horse.setNameOfHorse(normalizedName);
                horse.setPlacement(place.text().trim());
                horse.setVOdds(vOdd.text().trim());
                horse.setPOdds(pMap.getOrDefault(nr, ""));
                horse.setTrioOdds(trioMap.getOrDefault(nr, ""));
            } else {
                horse = com.example.travscraper.entity.ScrapedHorse.builder()
                        .date(date)
                        .track(bankode)
                        .lap(String.valueOf(lap))
                        .numberOfHorse(nr)
                        .nameOfHorse(normalizedName)
                        .placement(place.text().trim())
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

    private static int toYyyymmdd(LocalDate d) {
        return d.getYear() * 10000 + d.getMonthValue() * 100 + d.getDayOfMonth();
    }

    private static String slugify(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase();
    }

    private static String trackKey(String trackLike) {
        if (trackLike == null) return "";
        String k = slugify(trackLike);
        k = k.replace('-', ' ').replace('_', ' ');
        k = k.replaceAll("\\s+", " ").trim();
        return k;
    }

    private static String toKnownBankodOrNull(String trackLike) {
        String key = trackKey(trackLike);
        if (key.isBlank()) return null;
        return FULLNAME_TO_BANKODE.get(key);
    }

    private static String normalizeCellText(String s) {
        if (s == null) return "";
        return s.replace('\u00A0', ' ').trim();
    }

    private static String trimToMax(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max);
    }

    private static String normalizeHorseNameSimple(String raw) {
        String s = normalizeCellText(raw);
        if (s.isBlank()) return "";
        s = s.replace("'", "").replace("’", "");
        s = s.toUpperCase(Locale.ROOT);
        return trimToMax(s, 50);
    }
}
