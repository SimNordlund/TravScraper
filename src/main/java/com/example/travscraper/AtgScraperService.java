package com.example.travscraper;

import com.example.travscraper.entity.FutureHorse;
import com.example.travscraper.entity.ResultHorse; //Changed!
import com.example.travscraper.entity.ScrapedHorse;
import com.example.travscraper.entity.ScrapedHorseKey;
import com.example.travscraper.repo.FutureHorseRepo;
import com.example.travscraper.repo.ResultHorseRepo; //Changed!
import com.example.travscraper.repo.ScrapedHorseRepo;
import com.example.travscraper.repo.StartListHorseRepo;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole; //Changed!
import com.microsoft.playwright.options.LoadState; //Changed!
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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets; //Changed!
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher; //Changed!
import java.util.regex.Pattern; //Changed!

@Slf4j
@Service
@RequiredArgsConstructor
public class AtgScraperService {

    private final ScraperProperties props;
    private final ScrapedHorseRepo repo;
    private final FutureHorseRepo futureRepo;
    private final StartListHorseRepo startListRepo;

    private final ResultHorseRepo resultRepo; //Changed!

    private Playwright playwright;
    private Browser browser;
    private BrowserContext ctx;
    private final ReentrantLock lock = new ReentrantLock();

    private static final DateTimeFormatter URL_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter YYMMDD_FORMAT = DateTimeFormatter.ofPattern("yyMMdd"); //Changed!

    private static final Pattern PLACERING_WITH_R = Pattern.compile("^(\\d{1,2})r$"); //Changed!
    private static final Pattern ISO_DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$"); //Changed!
    private static final Pattern HAS_LETTER = Pattern.compile(".*\\p{L}.*"); //Changed!

    private static final int RESULTAT_MAX_HORSES_PER_LAP = 999; //Changed!
    private static final int RESULTAT_MAX_ROWS_PER_HORSE = 999; //Changed!

    private static final Pattern TRACK_LAP_PATTERN = Pattern.compile("^\\s*(.+?)\\s*-\\s*(\\d+)\\s*$"); //Changed!
    private static final Pattern DIGITS_ONLY = Pattern.compile("(\\d{6,8})"); //Changed!

    private static final Pattern RESULT_HREF_PATTERN = Pattern.compile( //Changed!
            "/spel/(\\d{4}-\\d{2}-\\d{2})/vinnare/([^/]+)/lopp/(\\d+)/resultat" //Changed!
    ); //Changed!

    private static final Pattern PRIS_APOSTROPHE = Pattern.compile("^\\s*(\\d{1,6})\\s*['’]\\s*$"); //Changed!
    private static final Pattern PRIS_THOUSANDS = Pattern.compile("^\\s*(\\d{1,3}(?:[ \\u00A0\\.]\\d{3})+|\\d{4,})\\s*(?:kr)?\\s*$", Pattern.CASE_INSENSITIVE); //Changed!
    private static final Pattern PRIS_K = Pattern.compile("^\\s*(\\d{1,6})\\s*k\\s*$", Pattern.CASE_INSENSITIVE); //Changed!
    private static final Pattern ODDS_NUMBER = Pattern.compile("^\\s*(\\d{1,4})(?:[\\.,](\\d{1,2}))?\\s*$"); //Changed!


    //Changed! Cookie selectors (för när init-scriptet inte hinner / blockeras)
    private static final String SEL_COOKIE_BUTTONS = //Changed!
            "button:has-text(\"Tillåt alla\"):visible, " + //Changed!
                    "button:has-text(\"Avvisa\"):visible, " + //Changed!
                    "button:has-text(\"Godkänn alla cookies\"):visible, " + //Changed!
                    "button:has-text(\"Jag förstår\"):visible"; //Changed!

    //Changed! Mer robusta selectors (dubbelcitat för Playwright :has-text)
    private static final String SEL_EXPAND_ALL = //Changed!
            "button[data-test-id=\"expand-startlist-view\"], button:has-text(\"Utöka alla\"), button:has-text(\"Utöka\")"; //Changed!

    //Changed! Inkludera även “Visa mer” som vissa vyer använder
    private static final String SEL_MER_INFO_VISIBLE = //Changed!
            "button[data-test-id*=\"previous-starts\"][data-test-id*=\"show-more\"]:visible, " + //Changed!
                    "button[data-test-id=\"previous-starts-toggle-show-more-button\"]:visible, " + //Changed!
                    "button:has-text(\"Mer info\"):visible, " + //Changed!
                    "button:has-text(\"Visa mer\"):visible, " + //Changed!
                    "button:has-text(\"Visa mer info\"):visible"; //Changed!

    //Changed! ATG renderar ibland tabellen inline (inte modal). Vi matchar båda.
    private static final String SEL_PREVSTARTS_WRAPPER_ANY = //Changed!
            "#previous-starts-table-container, " + //Changed!
                    "div[class*=\"PreviousStarts-styles--tableWrapper\"], " + //Changed!
                    "div[class*=\"previousStarts-styles--tableWrapper\"], " + //Changed!
                    "div[class*=\"PreviousStarts\"][class*=\"tableWrapper\"], " + //Changed!
                    "div[class*=\"previousStarts\"][class*=\"tableWrapper\"]"; //Changed!

    private static final String SEL_PREVSTARTS_ROWS_ANY = //Changed!
            "#previous-starts-table-container tbody tr, " + //Changed!
                    "div[class*=\"PreviousStarts-styles--tableWrapper\"] tbody tr, " + //Changed!
                    "div[class*=\"previousStarts-styles--tableWrapper\"] tbody tr, " + //Changed!
                    "div[class*=\"PreviousStarts\"][class*=\"tableWrapper\"] tbody tr, " + //Changed!
                    "div[class*=\"previousStarts\"][class*=\"tableWrapper\"] tbody tr, " + //Changed!
                    "[data-test-id=\"result-date\"], [data-test-id=\"result-date\"] span"; //Changed!



    private static final Pattern TIME_VALUE = Pattern.compile("(?:\\d+\\.)?(\\d{1,2})[\\.,](\\d{1,2})"); //Changed!


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
            Map.entry("ostersund", "Ös"),

            Map.entry("bjerke", "Bj"),
            Map.entry("bodo", "Bd"),
            Map.entry("biri", "Br"),
            Map.entry("bergen", "Bt"),
            Map.entry("drammen", "Dr"),
            Map.entry("forus", "Fs"),
            Map.entry("harstad", "Ha"),
            Map.entry("haugaland", "Ht"),
            Map.entry("jarlsberg", "Ja"),
            Map.entry("klosterskogen", "Kl"),
            Map.entry("leangen", "Le"),
            Map.entry("momarken", "Mo"),
            Map.entry("sorlandet", "Sö"),

            Map.entry("arhus", "Aa"),
            Map.entry("billund", "Bi"),
            Map.entry("bornholm", "Bm"),
            Map.entry("charlottenlund", "Ch"),
            Map.entry("nykobing", "Ny"),
            Map.entry("odense", "Od"),
            Map.entry("skive", "Se"),
            Map.entry("alborg", "Ål")
    );

    private static final Map<String, String> BANKODE_TO_SLUG;
    static {
        Map<String, String> m = new HashMap<>();
        FULLNAME_TO_BANKODE.forEach((slug, code) -> m.put(code, slug));
        BANKODE_TO_SLUG = Collections.unmodifiableMap(m);
    }

    private static int toYyyymmdd(LocalDate d) {
        return d.getYear() * 10000 + d.getMonthValue() * 100 + d.getDayOfMonth();
    }

    private static String slugify(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase();
    }

    private static final String DEFAULT_BANKOD = "XX"; //Changed!


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

    private List<String> tracksForForeign(LocalDate date) {
        List<String> codes = new ArrayList<>();
        codes.add("Bd"); codes.add("Br"); codes.add("Bt"); codes.add("Dr");
        codes.add("Fs"); codes.add("Ha"); codes.add("Ht"); codes.add("Ja");
        codes.add("Kl"); codes.add("Le"); codes.add("Mo"); codes.add("Sö");

        codes.add("Aa"); codes.add("Bi"); codes.add("Bm"); codes.add("Ch");
        codes.add("Ny"); codes.add("Od"); codes.add("Se"); codes.add("Ål");

        if (codes.isEmpty()) {
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

    @PostConstruct
    void initBrowser() {
        playwright = Playwright.create();
        browser = playwright.chromium().launch(
                new BrowserType.LaunchOptions()
                        .setHeadless(true)
                        .setArgs(List.of("--disable-blink-features=AutomationControlled")) //Changed!
        );
        log.info("🖥️  Headless browser launched");
    }

    @PreDestroy
    void closeBrowser() {
        if (ctx != null) ctx.close();
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
    }

    @Scheduled(cron = "0 55 23 * * *", zone = "Europe/Stockholm")
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

    @Scheduled(cron = "0 55 23 * * *", zone = "Europe/Stockholm")
    public void scrapeForeign() {
        if (!lock.tryLock()) {
            log.warn("⏳ Previous scrape still running – skipping");
            return;
        }
        try {
            LocalDate end = Optional.ofNullable(props.getEndDateResultForeign())
                    .orElse(LocalDate.now(ZoneId.of("Europe/Stockholm")).minusDays(0));
            LocalDate start = Optional.ofNullable(props.getStartDateForeign())
                    .orElse(end.minusDays(0));

            for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
                log.info("📆  Scraping danskar och norskar RESULTS {}", date);
                List<String> tracks = tracksForForeign(date);
                for (String track : tracks) {
                    processDateTrack(date, track);
                }
            }
        } finally {
            lock.unlock();
        }
    }

    @Scheduled(cron = "0 55 23 * * *", zone = "Europe/Stockholm")
    public void scrapeFuture() {
        if (!lock.tryLock()) {
            log.warn("⏳ Previous scrape still running – skipping (future)");
            return;
        }
        try {
            LocalDate end = Optional.ofNullable(props.getEndDateFuture())
                    .orElse(LocalDate.now(ZoneId.of("Europe/Stockholm")));
            LocalDate start = Optional.ofNullable(props.getStartDateFuture())
                    .orElse(end);

            for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
                log.info("📆  Scraping FUTURE {}", date);
                List<String> tracks = tracksFor(date);
                for (String track : tracks) {
                    processDateTrackFuture(date, track);
                }
            }
        } finally {
            lock.unlock();
        }
    }

    private void ensureContext() { //Changed!
        if (ctx != null) return; //Changed!

        ctx = browser.newContext( //Changed!
                new Browser.NewContextOptions() //Changed!
                        .setLocale("sv-SE") //Changed!
                        .setTimezoneId("Europe/Stockholm") //Changed!
                        .setGeolocation(59.33, 18.06) //Changed!
                        .setPermissions(List.of("geolocation")) //Changed!
                        .setViewportSize(1600, 900) //Changed!
                        .setUserAgent( //Changed!
                                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                                        "AppleWebKit/537.36 (KHTML, like Gecko) " +
                                        "Chrome/125.0.0.0 Safari/537.36") //Changed!
        ); //Changed!

        ctx.addInitScript(""" 
          () => {
            //Changed! försök minska anti-bot-detektering
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
        """); //Changed!
    } //Changed!

    private void processDateTrack(LocalDate date, String track) {
        ensureContext(); //Changed!

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
                try { Thread.sleep(600 + (int) (Math.random() * 1200)); } catch (InterruptedException ignored) {}
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

    private void processDateTrackFuture(LocalDate date, String track) {
        ensureContext(); //Changed!

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

                try {
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
                    if (e.getMessage() != null && e.getMessage().contains("Timeout")) {
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
        String slug = slugify(active.attr("data-test-id").substring("calendar-menu-track-".length()));
        if (!slug.equals(expected.toLowerCase())) {
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

    private static String trackKey(String trackLike) { //Changed!
        if (trackLike == null) return ""; //Changed!
        String k = slugify(trackLike); //Changed!
        k = k.replace('-', ' ').replace('_', ' '); //Changed!
        k = k.replaceAll("\\s+", " ").trim(); //Changed!
        return k; //Changed!
    } //Changed!

    private static String toKnownBankodOrNull(String trackLike) { //Changed!
        String key = trackKey(trackLike); //Changed!
        if (key.isBlank()) return null; //Changed!
        return FULLNAME_TO_BANKODE.get(key); //Changed!
    } //Changed!


    private Map<String, String> extractTrioMap(Page page) {
        Map<String, String> map = new HashMap<>();
        Document doc = Jsoup.parse(page.content());

        Element comboLabel = doc.selectFirst("span:matchesOwn(^\\s*Rätt\\skombination:?)");
        Element oddsLabel = doc.selectFirst("span:matchesOwn(^\\s*Odds:?)");
        if (comboLabel == null || oddsLabel == null) return map;

        String combo = comboLabel.parent().selectFirst("span[class*=\"--value\"]").text().trim();
        String odds = oddsLabel.parent().selectFirst("span[class*=\"--value\"]").text().trim();
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
            Element vOdd = tr.selectFirst("[data-test-id=startlist-cell-vodds]");
            if (place == null || split == null || vOdd == null) continue;

            String[] parts = split.text().trim().split("\\s+", 2);
            String nr = parts.length > 0 ? parts[0] : "";
            String name = parts.length > 1 ? parts[1] : "";

            String normalizedName = normalizeHorseNameSimple(name); //Changed!

            String bankode = toKnownBankodOrNull(track); //Changed!
            if (bankode == null) { //Changed!
                log.warn("⚠️  Okänd bana '{}' -> skippar RESULTS (ScrapedHorse) helt", track); //Changed!
                continue; //Changed!
            } //Changed!

            ScrapedHorseKey key = new ScrapedHorseKey(date, bankode, String.valueOf(lap), nr);

            Optional<ScrapedHorse> existingHorseOpt = repo.findById(key);

            ScrapedHorse horse;
            if (existingHorseOpt.isPresent()) {
                horse = existingHorseOpt.get();

                horse.setNameOfHorse(normalizedName); //Changed!
                horse.setPlacement(place.text().trim());
                horse.setVOdds(vOdd.text().trim());
                horse.setPOdds(pMap.getOrDefault(nr, ""));
                horse.setTrioOdds(trioMap.getOrDefault(nr, ""));
            } else {
                horse = ScrapedHorse.builder()
                        .date(date).track(bankode).lap(String.valueOf(lap))
                        .numberOfHorse(nr).nameOfHorse(normalizedName).placement(place.text().trim()) //Changed!
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

    private void parseAndPersistFuture(String html, LocalDate date, String track, int lap) {
        Elements rows = Jsoup.parse(html).select("tr[data-test-id^=horse-row]");
        if (rows.isEmpty()) return;

        List<FutureHorse> toSave = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        List<ResultHorse> oddsUpdates = new ArrayList<>(); //Changed!

        for (Element tr : rows) {
            String nr = extractStartNumber(tr);
            if (nr.isBlank()) {
                log.debug("⏭️  Skipping row without start number {} {} lap {}", date, track, lap);
                continue;
            }
            if (!seen.add(nr)) {
                log.debug("⏭️  Duplicate start number {} on {} {} lap {}, skipping", nr, date, track, lap);
                continue;
            }

            Element split = tr.selectFirst("[startlist-export-id^=startlist-cell-horse-split-export]");
            String name;
            if (split != null) {
                String[] parts = split.text().trim().split("\\s+", 2);
                name = (parts.length > 1) ? parts[1] : (parts.length > 0 ? parts[0] : "");
            } else {
                name = tr.text();
            }

            String normalizedName = normalizeHorseNameSimple(name); //Changed!

            Element vOddEl = tr.selectFirst("[data-test-id=startlist-cell-vodds]");
            String vOdds = vOddEl != null ? vOddEl.text().trim() : "";

            String bankode = toKnownBankodOrNull(track);
            if (bankode == null) {
                log.warn("⚠️  Okänd bana '{}' -> skippar FUTURE (FutureHorse) helt", track);
                continue;
            }

            Optional<FutureHorse> existing = futureRepo
                    .findByDateAndTrackAndLapAndNumberOfHorse(
                            date, bankode, String.valueOf(lap), nr);

            FutureHorse fh;
            if (existing.isPresent()) {
                fh = existing.get();
                fh.setNameOfHorse(normalizedName); //Changed!
                fh.setVOdds(vOdds);
            } else {
                fh = FutureHorse.builder()
                        .date(date)
                        .track(bankode)
                        .lap(String.valueOf(lap))
                        .numberOfHorse(nr)
                        .nameOfHorse(normalizedName) //Changed!
                        .vOdds(vOdds)
                        .build();
            }

            toSave.add(fh);

            ResultHorse rhOdds = buildOrUpdateResultOddsForFuture(date, bankode, lap, normalizedName, vOdds); //Changed!
            if (rhOdds != null) oddsUpdates.add(rhOdds); //Changed!
        }

        try {
            futureRepo.saveAll(toSave);
        } catch (DataIntegrityViolationException dive) {
            log.warn("🔁 saveAll collided with unique constraint, retrying per row (future) on {} {} lap {}", date, track, lap);
            for (FutureHorse fh : toSave) {
                try {
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

        //Changed! Spara odds till resultat samtidigt
        if (!oddsUpdates.isEmpty()) { //Changed!
            try { //Changed!
                resultRepo.saveAll(oddsUpdates); //Changed!
                log.info("💾 (future) Uppdaterade {} odds i RESULTAT för {} {} lap {}", oddsUpdates.size(), date, track, lap); //Changed!
            } catch (DataIntegrityViolationException dive) { //Changed!
                log.warn("🔁 (future) saveAll odds krockade, retrying per row på {} {} lap {}: {}", //Changed!
                        date, track, lap, dive.getMostSpecificCause().getMessage()); //Changed!
                for (ResultHorse rh : oddsUpdates) { //Changed!
                    try { //Changed!
                        resultRepo.save(rh); //Changed!
                    } catch (DataIntegrityViolationException ignored) { //Changed!
                        log.warn("⚠️  (future) Kunde inte spara odds datum={} bankod={} lopp={} namn={}", //Changed!
                                rh.getDatum(), rh.getBankod(), rh.getLopp(), rh.getNamn()); //Changed!
                    } //Changed!
                } //Changed!
            } //Changed!
        } //Changed!

        log.info("💾 (future) Saved/updated {} horses for {} {} lap {}", toSave.size(), date, track, lap);
    }


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

    //Changed!
    public void runResultatPopupScrape() { //Changed!
        scrapeResultatPopupsOnly(); //Changed!
    } //Changed!

    //Changed!
    public void scrapeResultatPopupsOnly() { //Changed!
        if (!lock.tryLock()) { //Changed!
            log.warn("⏳ Previous scrape still running – skipping (resultat popups)"); //Changed!
            return; //Changed!
        } //Changed!
        try { //Changed!
            LocalDate end = Optional.ofNullable(props.getEndDateResultatPopup()) //Changed!
                    .orElse(LocalDate.now(ZoneId.of("Europe/Stockholm")).minusDays(1)); //Changed!
            LocalDate start = Optional.ofNullable(props.getStartDateResultatPopup()) //Changed!
                    .orElse(end); //Changed!

            for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) { //Changed!
                log.info("📆  Scraping RESULTAT (popup) {}", date); //Changed!
                List<String> tracks = tracksFor(date); //Changed!
                for (String track : tracks) { //Changed!
                    processDateTrackResultatPopups(date, track); //Changed!
                } //Changed!
            } //Changed!
        } finally { //Changed!
            lock.unlock(); //Changed!
        } //Changed!
    } //Changed!


    //Changed!
    private void processDateTrackResultatPopups(LocalDate date, String track) { //Changed!
        ensureContext(); //Changed!

        int consecutiveMisses = 0; //Changed!
        for (int lap = 1; lap <= 15; lap++) { //Changed!
            String url = String.format( //Changed!
                    "https://www.atg.se/spel/%s/vinnare/%s/lopp/%d", //Changed!
                    date.format(URL_DATE_FORMAT), track, lap); //Changed!

            try (Page page = ctx.newPage()) { //Changed!
                Page.NavigateOptions nav = new Page.NavigateOptions() //Changed!
                        .setWaitUntil(WaitUntilState.NETWORKIDLE) //Changed!
                        .setTimeout(60_000); //Changed!

                page.navigate(url, nav); //Changed!

                if (page.url().contains("/spel/kalender/")) { //Changed!
                    if (++consecutiveMisses >= 2) break; //Changed!
                    continue; //Changed!
                } //Changed!

                String finalUrl = page.url(); //Changed!
                if (finalUrl.contains("/lopp/") && !finalUrl.contains("/lopp/" + lap)) { //Changed!
                    log.info("↪️  (resultat) lopp {} redirectade till {}, hoppar", lap, finalUrl); //Changed!
                    if (++consecutiveMisses >= 2) break; //Changed!
                    continue; //Changed!
                } //Changed!

                //Changed! Vänta på cookie/rows, och stäng cookie om den är i vägen
                page.waitForSelector( //Changed!
                        "button:has-text(\"Tillåt alla\"):visible, " + //Changed!
                                "button:has-text(\"Avvisa\"):visible, " + //Changed!
                                "tr[data-test-id^=horse-row]", //Changed!
                        new Page.WaitForSelectorOptions().setTimeout(60_000)); //Changed!

                dismissCookiesIfPresent(page); //Changed!

                page.waitForSelector("tr[data-test-id^=horse-row]", //Changed!
                        new Page.WaitForSelectorOptions().setTimeout(60_000)); //Changed!

                if (isCancelledRace(page)) { //Changed!
                    if (++consecutiveMisses >= 2) break; //Changed!
                    continue; //Changed!
                } //Changed!

                if (!isCorrectTrack(page, track, date)) return; //Changed!

                consecutiveMisses = 0; //Changed!

                scrapeResultatFromPopups(page, date, track, lap); //Changed!

            } catch (PlaywrightException e) { //Changed!
                log.warn("⚠️  (resultat) Playwright-fel på {}: {}", url, e.getMessage()); //Changed!
                if (e.getMessage() != null && e.getMessage().contains("Timeout")) { //Changed!
                    if (++consecutiveMisses >= 2) break; //Changed!
                    continue; //Changed!
                } //Changed!
                break; //Changed!
            } //Changed!
        } //Changed!
    } //Changed!

    //Changed!
    private void dismissCookiesIfPresent(Page page) { //Changed!
        try { //Changed!
            Locator cookie = page.locator(SEL_COOKIE_BUTTONS); //Changed!
            if (cookie.count() > 0) { //Changed!
                robustClick(cookie.first(), 10_000); //Changed!
                try { page.waitForTimeout(250); } catch (PlaywrightException ignored) {} //Changed!
            } //Changed!
        } catch (PlaywrightException ignored) { //Changed!
        } //Changed!
    } //Changed!

    private void robustClick(Locator btn, int timeoutMs) { //Changed!
        try { //Changed!
            btn.scrollIntoViewIfNeeded(); //Changed!
        } catch (PlaywrightException ignored) { //Changed!
        } //Changed!

        try { //Changed!
            btn.click(new Locator.ClickOptions().setTimeout(timeoutMs).setForce(true)); //Changed!
            return; //Changed!
        } catch (PlaywrightException e) { //Changed!
            try { //Changed!
                btn.evaluate("el => el.click()"); //Changed!
            } catch (PlaywrightException ignored) { //Changed!
            } //Changed!
        } //Changed!
    } //Changed!

    private void clickExpandAllIfPresent(Page page) { //Changed!
        dismissCookiesIfPresent(page); //Changed!

        Locator expand = page.locator(SEL_EXPAND_ALL); //Changed!

        if (expand.count() == 0) { //Changed!
            try { //Changed!
                expand = page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName( //Changed!
                        Pattern.compile("Utöka\\s+alla|Utöka", Pattern.CASE_INSENSITIVE))); //Changed!
            } catch (PlaywrightException ignored) { //Changed!
            } //Changed!
        } //Changed!

        if (expand.count() <= 0) { //Changed!
            log.info("🟦 (resultat) Hittade ingen 'Utöka/Utöka alla' på {}", page.url()); //Changed!
            return; //Changed!
        } //Changed!

        Locator btn = expand.first(); //Changed!
        try { //Changed!
            robustClick(btn, 15_000); //Changed!
            try { page.waitForLoadState(LoadState.NETWORKIDLE); } catch (PlaywrightException ignored) {} //Changed!
        } catch (PlaywrightException e) { //Changed!
            log.warn("⚠️  (resultat) Kunde inte klicka 'Utöka/Utöka alla' på {}: {}", page.url(), e.getMessage()); //Changed!
        } //Changed!

        try { //Changed!
            page.waitForSelector(SEL_MER_INFO_VISIBLE, new Page.WaitForSelectorOptions() //Changed!
                    .setTimeout(6_000) //Changed!
                    .setState(WaitForSelectorState.ATTACHED)); //Changed!
        } catch (PlaywrightException ignored) { //Changed!
        } //Changed!
    } //Changed!

    //Changed!
    private void tryExpandSomeRowsIfMerInfoHidden(Page page) { //Changed!
        // Om “Mer info” finns i DOM men inte är visible: expandera ALLA rader (inte bara 3) //Changed!
        int rawText = page.locator("text=Mer info").count(); //Changed!
        int visibleButtons = page.locator(SEL_MER_INFO_VISIBLE).count(); //Changed!
        if (rawText == 0 || visibleButtons > 0) return; //Changed!

        Locator rows = page.locator("tr[data-test-id^=horse-row]"); //Changed!
        int n = Math.min(rows.count(), RESULTAT_MAX_HORSES_PER_LAP); //Changed! (was 3)

        for (int i = 0; i < n; i++) { //Changed!
            Locator row = rows.nth(i); //Changed!
            try { //Changed!
                // Försök klicka en expander-knapp om den finns, annars klicka hela raden //Changed!
                Locator expander = row.locator("button[aria-expanded], [aria-expanded]"); //Changed!
                if (expander.count() > 0) { //Changed!
                    String ae = expander.first().getAttribute("aria-expanded"); //Changed!
                    if (ae == null || !"true".equalsIgnoreCase(ae)) { //Changed!
                        robustClick(expander.first(), 3_000); //Changed!
                    } //Changed!
                } else { //Changed!
                    robustClick(row, 3_000); //Changed!
                } //Changed!
            } catch (PlaywrightException ignored) { //Changed!
            } //Changed!
        } //Changed!

        try { page.waitForTimeout(250); } catch (PlaywrightException ignored) {} //Changed!
    } //Changed!


    private Locator findMerInfoButtons(Page page) { //Changed!
        //Changed! Försök data-test-id (exakt + contains)
        Locator byDataTestId = page.locator( //Changed!
                "button[data-test-id=\"previous-starts-toggle-show-more-button\"]:visible, " + //Changed!
                        "button[data-test-id*=\"previous-starts\"][data-test-id*=\"show-more\"]:visible" //Changed!
        ); //Changed!
        if (byDataTestId.count() > 0) return byDataTestId; //Changed!

        //Changed! Text-varianter
        Locator byText = page.locator( //Changed!
                "button:has-text(\"Mer info\"):visible, " + //Changed!
                        "button:has-text(\"Visa mer\"):visible, " + //Changed!
                        "button:has-text(\"Visa mer info\"):visible, " + //Changed!
                        "a:has-text(\"Mer info\"):visible, " + //Changed!
                        "[role=button]:has-text(\"Mer info\"):visible" //Changed!
        ); //Changed!
        if (byText.count() > 0) return byText; //Changed!

        //Changed! Role-fallback
        try { //Changed!
            Locator byRole = page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions() //Changed!
                    .setName(Pattern.compile("Mer\\s+info|Visa\\s+mer(\\s+info)?", Pattern.CASE_INSENSITIVE))); //Changed!
            if (byRole.count() > 0) return byRole; //Changed!
        } catch (PlaywrightException ignored) { //Changed!
        } //Changed!

        //Changed! Sista fallback: visible buttons som innehåller text
        return page.locator("button:visible").filter(new Locator.FilterOptions().setHasText( //Changed!
                Pattern.compile("Mer\\s+info|Visa\\s+mer(\\s+info)?", Pattern.CASE_INSENSITIVE))); //Changed!
    } //Changed!

    private void scrapeResultatFromPopups(Page page, LocalDate meetingDate, String meetingTrackSlug, int meetingLap) { //Changed!
        clickExpandAllIfPresent(page); //Changed!
        tryExpandSomeRowsIfMerInfoHidden(page); //Changed!

        //Changed! Debug-räknare
        int textCount = page.locator("text=Mer info").count(); //Changed!
        int dataTestIdCount = page.locator("[data-test-id*=\"previous-starts\"][data-test-id*=\"show-more\"]").count(); //Changed!

        Locator merInfoButtons = findMerInfoButtons(page); //Changed!
        int total = merInfoButtons.count(); //Changed!

        if (total <= 0) { //Changed!
            log.info("🟦 (resultat) Inga 'Mer info/Visa mer' hittades på {} {} lopp {} (selCount={}, textCount={}, dataTestIdCount={})", //Changed!
                    meetingDate, meetingTrackSlug, meetingLap, total, textCount, dataTestIdCount); //Changed!
            return; //Changed!
        } //Changed!

        int toClick = Math.min(total, RESULTAT_MAX_HORSES_PER_LAP); //Changed!
        log.info("🟩 (resultat) Hittade {} 'Mer info/Visa mer' på {} {} lopp {} (klickar {})", //Changed!
                total, meetingDate, meetingTrackSlug, meetingLap, toClick); //Changed!

        for (int i = 0; i < toClick; i++) { //Changed!
            Locator btn = merInfoButtons.nth(i); //Changed!
            String horseName = ""; //Changed!
            Integer horseNr = null; //Changed!
            Locator scopeRow = null; //Changed!

            try { //Changed!
                Locator tr = btn.locator("xpath=ancestor::tr[1]"); //Changed!
                scopeRow = tr; //Changed!

                Locator split = tr.locator("[startlist-export-id^='startlist-cell-horse-split-export']"); //Changed!
                if (split.count() == 0) { //Changed!
                    Locator prev = tr.locator("xpath=preceding-sibling::tr[1]"); //Changed!
                    if (prev.count() > 0) { //Changed!
                        split = prev.locator("[startlist-export-id^='startlist-cell-horse-split-export']"); //Changed!
                    } //Changed!
                } //Changed!

                if (split.count() > 0) { //Changed!
                    String splitTxt = split.first().innerText().trim(); //Changed!
                    String[] parts = splitTxt.split("\\s+", 2); //Changed!

                    //Changed! startnummer
                    if (parts.length > 0) { //Changed!
                        String nrStr = parts[0].replaceAll("\\D+", ""); //Changed!
                        if (!nrStr.isBlank()) { //Changed!
                            try { //Changed!
                                horseNr = Integer.parseInt(nrStr); //Changed!
                            } catch (NumberFormatException ignored) { //Changed!
                            } //Changed!
                        } //Changed!
                    } //Changed!

                    //Changed! namn
                    horseName = (parts.length > 1) ? parts[1].trim() : splitTxt; //Changed!
                    horseName = normalizeHorseNameSimple(horseName); //Changed!
                } //Changed!

                robustClick(btn, 15_000); //Changed!

                Locator waitScope = btn.locator("xpath=ancestor::tr[1]"); //Changed!
                Locator rowsInScope = waitScope.locator("tbody tr"); //Changed!
                if (rowsInScope.count() == 0) { //Changed!
                    rowsInScope = waitScope.locator("[data-test-id=\"result-date\"]"); //Changed!
                } //Changed!
                if (rowsInScope.count() > 0) { //Changed!
                    rowsInScope.first().waitFor(new Locator.WaitForOptions().setTimeout(30_000)); //Changed!
                } else { //Changed!
                    page.waitForSelector(SEL_PREVSTARTS_ROWS_ANY, //Changed!
                            new Page.WaitForSelectorOptions().setTimeout(30_000).setState(WaitForSelectorState.ATTACHED)); //Changed!
                } //Changed!

                String fragment = waitScope.evaluate("el => el.outerHTML").toString(); //Changed!

                parseAndPersistResultatFromPreviousStarts(fragment, meetingDate, meetingTrackSlug, meetingLap, horseName, horseNr, i); //Changed!

                closePreviousStarts(page, btn, waitScope); //Changed!

            } catch (PlaywrightException e) { //Changed!
                log.warn("⚠️  (resultat) Kunde inte öppna/scrapa 'Mer info/Visa mer' på {} {} lopp {}: {}", //Changed!
                        meetingDate, meetingTrackSlug, meetingLap, e.getMessage()); //Changed!
                try { //Changed!
                    if (scopeRow != null) closePreviousStarts(page, btn, scopeRow); //Changed!
                    else closePreviousStarts(page, btn, null); //Changed!
                } catch (PlaywrightException ignored) { //Changed!
                } //Changed!
            } //Changed!
        } //Changed!

    } //Changed!

    private void closePreviousStarts(Page page, Locator merInfoBtn, Locator scopeRow) { //Changed!
        try { page.keyboard().press("Escape"); } catch (PlaywrightException ignored) {} //Changed!

        try { //Changed!
            Locator close = page.locator( //Changed!
                    "button[aria-label='Stäng'], button[aria-label='Close'], button:has-text(\"Stäng\"), button:has-text(\"Close\")" //Changed!
            ); //Changed!
            if (close.count() > 0) { //Changed!
                robustClick(close.first(), 5_000); //Changed!
            } //Changed!
        } catch (PlaywrightException ignored) { //Changed!
        } //Changed!

        //Changed! Inline-toggle: klicka samma knapp igen om scoped rows fortfarande verkar öppna
        try { //Changed!
            if (scopeRow != null) { //Changed!
                if (scopeRow.locator(SEL_PREVSTARTS_WRAPPER_ANY).count() > 0) { //Changed!
                    robustClick(merInfoBtn, 5_000); //Changed!
                } //Changed!
            } else { //Changed!
                //Changed! fallback utan scope
                if (page.locator(SEL_PREVSTARTS_WRAPPER_ANY).count() > 0) { //Changed!
                    robustClick(merInfoBtn, 5_000); //Changed!
                } //Changed!
            } //Changed!
        } catch (PlaywrightException ignored) { //Changed!
        } //Changed!
    } //Changed!

    private void parseAndPersistResultatFromPreviousStarts( //Changed!
                                                            String html, //Changed!
                                                            LocalDate meetingDate, //Changed!
                                                            String meetingTrackSlug, //Changed!
                                                            int meetingLap, //Changed!
                                                            String horseName, //Changed!
                                                            Integer horseNr, //Changed!
                                                            int horseIdx //Changed!
    ) { //Changed!
        Document doc = Jsoup.parse(html);

        Element container = doc.selectFirst("#previous-starts-table-container");
        if (container == null) {
            container = doc.selectFirst(
                    "div[class*=\"PreviousStarts-styles--tableWrapper\"], " +
                            "div[class*=\"previousStarts-styles--tableWrapper\"], " +
                            "div[class*=\"PreviousStarts\"][class*=\"tableWrapper\"], " +
                            "div[class*=\"previousStarts\"][class*=\"tableWrapper\"]"
            );
        }

        if (container == null) {
            log.warn("⚠️  (resultat) Hittade ingen previous-starts container på {} {} lopp {}",
                    meetingDate, meetingTrackSlug, meetingLap);
            return;
        }

        Elements rows = container.select("tbody tr");
        if (rows.isEmpty()) rows = container.select("tr");

        if (rows.isEmpty()) {
            log.warn("⚠️  (resultat) Inga rader i previous-starts på {} {} lopp {}",
                    meetingDate, meetingTrackSlug, meetingLap);
            return;
        }

        //Changed! horseNr ignoreras här med flit. Previous-starts rader hör inte ihop med dagens startnummer.
        String safeName = normalizeHorseNameSimple(horseName); //Changed!

        int kuskIdx = findHeaderIndex(container, Pattern.compile("\\bkusk\\b", Pattern.CASE_INSENSITIVE)); //Changed!

        List<ResultHorse> toSave = new ArrayList<>();
        int limit = Math.min(rows.size(), RESULTAT_MAX_ROWS_PER_HORSE);

        for (int i = 0; i < limit; i++) {
            Element tr = rows.get(i);

            Integer datum = extractDatumFromResultRow(tr);
            TrackLap tl = extractTrackLapFromResultRow(tr);
            if (datum == null || tl == null) continue;

            Elements tds = tr.select("td");
            int[] distSparIdx = findDistSparFromTds(tds);
            Integer distans = (distSparIdx[0] >= 0 ? distSparIdx[0] : null);
            Integer spar = (distSparIdx[1] >= 0 ? distSparIdx[1] : null);
            int distIdx = distSparIdx[2];

            Integer placering = extractPlaceringFromTds(tds, distIdx);
            TidInfo tidInfo = extractTidFromTds(tds, distIdx);
            Double tid = tidInfo.tid();
            String startmetod = tidInfo.startmetod();
            String galopp = tidInfo.galopp();

            Integer pris = extractPrisFromTds(tds, distIdx);
            Integer odds = extractOddsFromTds(tds, distIdx);

            String underlag = extractUnderlagFromTds(tds);
            if (underlag == null) underlag = "";

            String kusk = trimToMax(textAt(tds, kuskIdx), 80);

            ResultHorse rh = resultRepo
                    .findByDatumAndBankodAndLoppAndNamn(datum, tl.bankod(), tl.lopp(), safeName)
                    .orElseGet(() -> ResultHorse.builder()
                            .datum(datum)
                            .bankod(tl.bankod())
                            .lopp(tl.lopp())
                            .nr(0) //Changed! nya rader från popup-scrapern får alltid nr=0
                            .namn(safeName)
                            .build());

            if (!safeName.isBlank()) {
                rh.setNamn(safeName);
            }

            //Changed! Defaulta endast när nr saknas (null). Rör aldrig ett befintligt nr.
            if (rh.getNr() == null) { //Changed!
                rh.setNr(0); //Changed!
            } //Changed!

            if (distans != null) rh.setDistans(distans);
            if (spar != null) rh.setSpar(spar);
            if (placering != null) rh.setPlacering(placering);
            if (tid != null) rh.setTid(tid);
            if (startmetod != null && !startmetod.isBlank()) rh.setStartmetod(startmetod);
            if (galopp != null && !galopp.isBlank()) rh.setGalopp(galopp);

            rh.setPris(pris);
            rh.setOdds(odds);

            if (!underlag.isBlank()) rh.setUnderlag(underlag);
            else if (rh.getUnderlag() == null) rh.setUnderlag("");

            if (kusk != null && !kusk.isBlank()) rh.setKusk(kusk);

            toSave.add(rh);
        }

        if (toSave.isEmpty()) {
            log.info("🟦 (resultat) Inget att spara från 'Mer info' (häst='{}') på {} {} lopp {}",
                    horseName, meetingDate, meetingTrackSlug, meetingLap);
            return;
        }

        try {
            resultRepo.saveAll(toSave);
            log.info("💾 (resultat) Sparade/uppdaterade {} rader från 'Mer info' (nr=keep/default0) på {} {} lopp {}",
                    toSave.size(), meetingDate, meetingTrackSlug, meetingLap); //Changed!
        } catch (DataIntegrityViolationException dive) {
            log.warn("⚠️  (resultat) saveAll krockade, kör fallback rad-för-rad (nr=keep/default0) på {} {} lopp {}: {}",
                    meetingDate, meetingTrackSlug, meetingLap, dive.getMostSpecificCause().getMessage()); //Changed!

            for (ResultHorse rh : toSave) {
                try {
                    ResultHorse existing = resultRepo
                            .findByDatumAndBankodAndLoppAndNamn(rh.getDatum(), rh.getBankod(), rh.getLopp(), safeName)
                            .orElse(rh);

                    if (!rh.getNamn().isBlank()) existing.setNamn(rh.getNamn());

                    if (rh.getDistans() != null) existing.setDistans(rh.getDistans());
                    if (rh.getSpar() != null) existing.setSpar(rh.getSpar());
                    if (rh.getPlacering() != null) existing.setPlacering(rh.getPlacering());
                    if (rh.getTid() != null) existing.setTid(rh.getTid());
                    if (rh.getStartmetod() != null && !rh.getStartmetod().isBlank()) existing.setStartmetod(rh.getStartmetod());
                    if (rh.getGalopp() != null && !rh.getGalopp().isBlank()) existing.setGalopp(rh.getGalopp());
                    existing.setPris(rh.getPris());
                    existing.setOdds(rh.getOdds());

                    if (rh.getUnderlag() != null && !rh.getUnderlag().isBlank()) existing.setUnderlag(rh.getUnderlag());
                    else if (existing.getUnderlag() == null) existing.setUnderlag("");

                    if (rh.getKusk() != null && !rh.getKusk().isBlank()) existing.setKusk(rh.getKusk());

                    //Changed! Defaulta endast när nr saknas (null). Rör aldrig ett befintligt nr.
                    if (existing.getNr() == null) { //Changed!
                        existing.setNr(0); //Changed!
                    } //Changed!

                    resultRepo.save(existing);
                } catch (DataIntegrityViolationException ignored) {
                    log.warn("⚠️  (resultat) Kunde inte upserta datum={} bankod={} lopp={} nr=keep/default0",
                            rh.getDatum(), rh.getBankod(), rh.getLopp()); //Changed!
                }
            }
        }
    } //Changed!


    private static String extractUnderlagFromTds(Elements tds) { //Changed!
        if (tds == null || tds.isEmpty()) return ""; //Changed!

        String best = ""; //Changed!
        for (Element td : tds) { //Changed!
            String raw = normalizeCellText(td.text()); //Changed!
            if (raw.isBlank()) continue; //Changed!

            //Changed! Underlag i din vy kommer som (k n), (k), (n) osv
            if (!(raw.contains("(") && raw.contains(")"))) continue; //Changed!

            String cleaned = sanitizeUnderlag(raw); //Changed!
            if (cleaned.isBlank()) continue; //Changed!

            //Changed! Underlag är normalt kort, så vi prioriterar sista korta träffen
            if (cleaned.length() <= 4) best = cleaned; //Changed!
            else if (best.isBlank()) best = cleaned; //Changed!
        } //Changed!

        return best; //Changed!
    } //Changed!

    private static String sanitizeUnderlag(String raw) { //Changed!
        if (raw == null) return ""; //Changed!
        String t = normalizeCellText(raw).toLowerCase(Locale.ROOT); //Changed!
        if (t.isBlank()) return ""; //Changed!

        //Changed! ta bort parenteser och whitespace: "(k n)" -> "kn"
        t = t.replace("(", "").replace(")", ""); //Changed!
        t = t.replaceAll("\\s+", ""); //Changed!

        //Changed! behåll bara bokstäver
        t = t.replaceAll("[^a-z]", ""); //Changed!

        return t; //Changed!
    } //Changed!





    private static String trimToMax(String s, int max) { //Changed!
        if (s == null) return ""; //Changed!
        if (s.length() <= max) return s; //Changed!
        return s.substring(0, max); //Changed!
    } //Changed!



    private long stableResultId(LocalDate meetingDate, String meetingTrackSlug, int meetingLap, String horseName, int horseIdx, int rowIdx,
                                Integer datum, String bankod, Integer lopp) { //Changed!
        String key = meetingDate + "|" + meetingTrackSlug + "|" + meetingLap + "|" +
                (horseName == null ? "" : horseName) + "|" + horseIdx + "|" + rowIdx + "|" +
                datum + "|" + bankod + "|" + lopp; //Changed!
        UUID uuid = UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)); //Changed!
        return uuid.getMostSignificantBits() & Long.MAX_VALUE; //Changed!
    } //Changed!

    private Integer extractDatumFromResultRow(Element tr) { //Changed!
        //Changed! funkar för både <a> och <div>
        Element dateEl = tr.selectFirst("[data-test-id=result-date]"); //Changed!
        if (dateEl != null) { //Changed!
            //Changed! 1) Försök href om den finns (bara <a> har href)
            String href = dateEl.hasAttr("href") ? dateEl.attr("href") : ""; //Changed!
            if (href != null && !href.isBlank()) { //Changed!
                Matcher mh = RESULT_HREF_PATTERN.matcher(href); //Changed!
                if (mh.find()) { //Changed!
                    try { //Changed!
                        LocalDate d = LocalDate.parse(mh.group(1), URL_DATE_FORMAT); //Changed!
                        return Integer.parseInt(d.format(DateTimeFormatter.BASIC_ISO_DATE)); //Changed! yyyyMMdd
                    } catch (Exception ignored) { //Changed!
                    } //Changed!
                } //Changed!
            } //Changed!

            //Changed! 2) Fallback: läs texten (span eller direkt text)
            String txt = normalizeCellText(dateEl.text()); //Changed!
            if (!txt.isBlank()) { //Changed!
                //Changed! stöd även yyyy-MM-dd om det någon gång dyker upp
                if (ISO_DATE.matcher(txt).matches()) { //Changed!
                    try { //Changed!
                        LocalDate d = LocalDate.parse(txt, URL_DATE_FORMAT); //Changed!
                        return Integer.parseInt(d.format(DateTimeFormatter.BASIC_ISO_DATE)); //Changed!
                    } catch (Exception ignored) { //Changed!
                    } //Changed!
                } //Changed!

                Matcher m = DIGITS_ONLY.matcher(txt); //Changed!
                if (m.find()) { //Changed!
                    String digits = m.group(1).replaceAll("\\D+", ""); //Changed!
                    try { //Changed!
                        if (digits.length() == 6) return 20000000 + Integer.parseInt(digits); //Changed! yyMMdd -> 20yyMMdd
                        if (digits.length() == 8) return Integer.parseInt(digits); //Changed! yyyyMMdd
                    } catch (Exception ignored) { //Changed!
                    } //Changed!
                } //Changed!
            } //Changed!
        } //Changed!

        return null; //Changed!
    } //Changed!




    private TrackLap parseTrackLapText(String raw) { //Changed!
        String t = normalizeCellText(raw); //Changed!
        if (t.isBlank()) return null; //Changed!

        //Changed! Stoppa datum som annars matchar TRACK_LAP_PATTERN (t.ex. 2025-10-06 -> "2025-10" + "06")
        if (ISO_DATE.matcher(t).matches()) return null; //Changed!
        if (t.matches("^\\d{6}$") || t.matches("^\\d{8}$")) return null; //Changed!

        Matcher m = TRACK_LAP_PATTERN.matcher(t); //Changed!
        if (!m.matches()) return null; //Changed!

        String trackName = m.group(1).trim(); //Changed!
        if (!HAS_LETTER.matcher(trackName).matches()) return null; //Changed! // stoppar "2025-10"

        int lopp; //Changed!
        try { //Changed!
            lopp = Integer.parseInt(m.group(2)); //Changed!
        } catch (NumberFormatException e) { //Changed!
            return null; //Changed!
        } //Changed!

        return new TrackLap(toResultBankod(trackName), lopp); //Changed!
    } //Changed!

    private TrackLap extractTrackLapFromResultRow(Element tr) { //Changed!
        //Changed! funkar för både <a> och <div>
        Element dateEl = tr.selectFirst("[data-test-id=result-date]"); //Changed!
        if (dateEl != null) { //Changed!
            String href = dateEl.hasAttr("href") ? dateEl.attr("href") : ""; //Changed!
            if (href != null && !href.isBlank()) { //Changed!
                Matcher mh = RESULT_HREF_PATTERN.matcher(href); //Changed!
                if (mh.find()) { //Changed!
                    String trackSlug = mh.group(2).trim(); //Changed!
                    try { //Changed!
                        int lopp = Integer.parseInt(mh.group(3)); //Changed!
                        String bankod = toResultBankod(trackSlug); //Changed!
                        return new TrackLap(bankod, lopp); //Changed!
                    } catch (NumberFormatException ignored) { //Changed!
                    } //Changed!
                } //Changed!
            } //Changed!
        } //Changed!

        //Changed! Fallback: leta i alla td men ignorera datum och kräver bokstav i "bana"-delen
        for (Element td : tr.select("td")) { //Changed!
            TrackLap tl = parseTrackLapText(td.text()); //Changed!
            if (tl != null) return tl; //Changed!
        } //Changed!

        return null; //Changed!
    } //Changed!



    private record TrackLap(String bankod, Integer lopp) {} //Changed!

    private static int[] findDistSparFromTds(Elements tds) { //Changed!
        // return [distans, spar, tdIndex] där -1 betyder "hittades inte" //Changed!
        int[] out = new int[]{-1, -1, -1}; //Changed!
        if (tds == null || tds.isEmpty()) return out; //Changed!

        //Changed! Tillåt även "1700:" (utan spår) => default spår=1
        Pattern p = Pattern.compile("^(\\d{3,4})\\s*:\\s*(\\d{1,2})?\\s*$"); //Changed!
        for (int i = 0; i < tds.size(); i++) { //Changed!
            String txt = normalizeCellText(tds.get(i).text()); //Changed!
            Matcher m = p.matcher(txt); //Changed!
            if (m.matches()) { //Changed!
                try { //Changed!
                    out[0] = Integer.parseInt(m.group(1)); //Changed!
                    String g2 = m.group(2); //Changed!
                    out[1] = (g2 == null || g2.isBlank()) ? 1 : Integer.parseInt(g2); //Changed!
                    out[2] = i; //Changed!
                    return out; //Changed!
                } catch (NumberFormatException ignored) { //Changed!
                } //Changed!
            } //Changed!
        } //Changed!

        //Changed! Fallback: "1700" (utan ":spår") => spår=1 om nästa cell ser ut som tid (t.ex. "19,0")
        Pattern distOnly = Pattern.compile("^(\\d{3,4})\\s*(?:m)?\\s*$", Pattern.CASE_INSENSITIVE); //Changed!
        for (int i = 0; i < tds.size(); i++) { //Changed!
            String txt = normalizeCellText(tds.get(i).text()); //Changed!
            Matcher m = distOnly.matcher(txt); //Changed!
            if (!m.matches()) continue; //Changed!

            int dist; //Changed!
            try { //Changed!
                dist = Integer.parseInt(m.group(1)); //Changed!
            } catch (NumberFormatException e) { //Changed!
                continue; //Changed!
            } //Changed!

            if (dist < 600 || dist > 4000) continue; //Changed!

            String next = (i + 1 < tds.size()) ? normalizeCellText(tds.get(i + 1).text()) : ""; //Changed!
            boolean nextLooksLikeTime = false; //Changed!
            if (!next.isBlank()) { //Changed!
                TidInfo ti = parseTidCell(next, true); //Changed!
                nextLooksLikeTime = (ti.tid() != null || ti.startmetod() != null || ti.galopp() != null
                        || TIME_VALUE.matcher(next).find()); //Changed!
            } //Changed!
            if (!nextLooksLikeTime) continue; //Changed!

            out[0] = dist; //Changed!
            out[1] = 1; //Changed!
            out[2] = i; //Changed!
            return out; //Changed!
        } //Changed!

        return out; //Changed!
    } //Changed!


    private static Integer mapPlaceringValue(String raw) { //Changed!
        String t = normalizeCellText(raw).toLowerCase(Locale.ROOT); //Changed!
        if (t.isBlank()) return null; //Changed!

        String token = t.split("\\s+")[0].replaceAll("[^0-9\\p{L}]", ""); //Changed!
        if (token.isBlank()) return null; //Changed!

        Matcher mr = PLACERING_WITH_R.matcher(token); //Changed!
        if (mr.matches()) token = mr.group(1); //Changed!

        if (token.equals("k") || token.equals("p") || token.equals("str") || token.equals("d")) return 99; //Changed!

        if (!token.matches("^\\d+$")) return null; //Changed!
        if (token.length() > 2) return null; //Changed!

        try { //Changed!
            int v = Integer.parseInt(token); //Changed!
            if (v == 0 || v == 9) return 15; //Changed!
            return v; //Changed!
        } catch (NumberFormatException e) { //Changed!
            return null; //Changed!
        } //Changed!
    } //Changed!


    private static Integer extractPlaceringFromTds(Elements tds, int distIdx) { //Changed!
        if (tds == null || tds.isEmpty()) return null; //Changed!

        //Changed! 1) fetstilad cell först (det är placering i din vy)
        for (int i = 0; i < tds.size(); i++) { //Changed!
            Element td = tds.get(i); //Changed!
            if (td.selectFirst("span[style*=font-weight]") != null) { //Changed!
                Integer v = mapPlaceringValue(td.text()); //Changed!
                if (v != null) return v; //Changed!
            } //Changed!
        } //Changed!

        //Changed! 2) fallback: leta före distans:spår-kolumnen
        int max = (distIdx >= 0 ? distIdx : tds.size()); //Changed!
        for (int i = 0; i < max; i++) { //Changed!
            Integer v = mapPlaceringValue(tds.get(i).text()); //Changed!
            if (v != null) return v; //Changed!
        } //Changed!

        return null; //Changed!
    } //Changed!


    private record TidInfo(Double tid, String startmetod, String galopp) {} //Changed!

    private static TidInfo extractTidFromTds(Elements tds, int distIdx) { //Changed!
        if (tds == null || tds.isEmpty()) return new TidInfo(null, null, null);

        // Oftast ligger tid direkt efter "distans : spår"
        if (distIdx >= 0 && distIdx + 1 < tds.size()) {
            TidInfo info = parseTidCell(tds.get(distIdx + 1).text(), true); //Changed!
            if (info.tid != null || info.startmetod != null || info.galopp != null) return info;
        }

        // Fallback: leta efter första rimliga tid-cellen
        for (int i = 0; i < tds.size(); i++) {
            String raw = normalizeCellText(tds.get(i).text());
            if (raw.contains(":")) continue; // (hoppa distans:spår)

            TidInfo info = parseTidCell(raw); //Changed!
            if (info.tid == null && info.startmetod == null && info.galopp == null) continue;

            // Tillåt alltid 99 (dist/kub/u/d/vmk)
            if (info.tid != null && Double.compare(info.tid, 99.0) == 0) return info;

            // Undvik odds som ofta har två decimaler (t.ex. 28,81)
            if (info.tid != null && info.startmetod == null && info.galopp == null && raw.matches(".*\\d+[\\.,]\\d{2}.*")) {
                continue;
            }

            // Rimlig tid-range
            if (info.tid != null && info.tid >= 8.0 && info.tid <= 60.0) return info;

            // Om vi åtminstone hittade a/g, returnera det
            if (info.startmetod != null || info.galopp != null) return info;
        }

        return new TidInfo(null, null, null);
    } //Changed!

    private static Integer parseFirstInt(String s) { //Changed!
        String txt = normalizeCellText(s); //Changed!
        Matcher m = Pattern.compile("(\\d{1,4})").matcher(txt); //Changed!
        if (!m.find()) return null; //Changed!
        try { //Changed!
            return Integer.parseInt(m.group(1)); //Changed!
        } catch (NumberFormatException e) { //Changed!
            return null; //Changed!
        } //Changed!
    } //Changed!

    private static TidInfo parseTidCell(String raw) { //Changed!
        return parseTidCell(raw, false); //Changed!
    } //Changed!

    private static TidInfo parseTidCell(String raw, boolean integerNoCommaMeans99) { //Changed!
        String t = normalizeCellText(raw).toLowerCase(Locale.ROOT);
        if (t.isBlank()) return new TidInfo(null, null, null);

        t = t.replaceAll("[()\\s]", "");

        // Plocka ut bokstavsdelen (suffix/ord)
        String letters = t.replaceAll("[0-9\\.,]", "");

        String startmetod = letters.contains("a") ? "a" : null;
        String galopp = letters.contains("g") ? "g" : null;

        // Specialfall som alltid ska ge tid = 99
        boolean force99 = letters.contains("dist")
                || letters.contains("kub")
                || letters.contains("vmk")
                || letters.contains("u")
                || letters.contains("d");

        Double time = null;
        Matcher m = TIME_VALUE.matcher(t);
        if (m.find()) {
            try {
                time = Double.parseDouble(m.group(1) + "." + m.group(2));
            } catch (NumberFormatException ignored) {
            }
        }

        if (force99) return new TidInfo(99.0, startmetod, galopp);

        //Changed! Om det inte finns komma/punkt i tid-kolumnen (t.ex. "14", "10", "10ag") => tid=99
        if (time == null) { //Changed!
            boolean hasSep = t.contains(",") || t.contains("."); //Changed!
            String digits = t.replaceAll("\\D+", ""); //Changed!
            if (!hasSep && !digits.isBlank() && digits.length() <= 2) { //Changed!
                if (integerNoCommaMeans99 || !letters.isBlank()) { //Changed!
                    return new TidInfo(99.0, startmetod, galopp); //Changed!
                } //Changed!
            } //Changed!
        } //Changed!

        return new TidInfo(time, startmetod, galopp);
    } //Changed!

    private static String normalizeCellText(String s) { //Changed!
        if (s == null) return ""; //Changed!
        return s.replace('\u00A0', ' ').trim(); //Changed!
    } //Changed!

    private static String normalizeHorseNameSimple(String raw) { //Changed!
        String s = normalizeCellText(raw); //Changed!
        if (s.isBlank()) return ""; //Changed!
        s = s.replace("'", "").replace("’", ""); //Changed!
        s = s.toUpperCase(Locale.ROOT); //Changed!
        return trimToMax(s, 50); //Changed!
    } //Changed!

    private static final int RESULT_BANKOD_MAX_LEN = 20; //Changed!

    private static String toResultBankod(String trackLike) { //Changed!
        if (trackLike == null || trackLike.isBlank()) return DEFAULT_BANKOD; //Changed!

        String key = trackKey(trackLike); //Changed!
        String code = FULLNAME_TO_BANKODE.get(key); //Changed!
        if (code != null && !code.isBlank()) return code; //Changed!

        String fallback = trimToMax(key, RESULT_BANKOD_MAX_LEN); //Changed!
        if (fallback.isBlank()) return DEFAULT_BANKOD; //Changed!

        log.warn("⚠️  Okänd bana '{}' (key='{}'), använder '{}' som bankod i RESULT-tabellen", //Changed!
                trackLike, key, fallback); //Changed!
        return fallback; //Changed!
    } //Changed!

    private static Integer extractPrisFromTds(Elements tds, int distIdx) { //Changed!
        if (tds == null || tds.isEmpty()) return 0; //Changed!

        int start = (distIdx >= 0 ? Math.min(distIdx + 2, tds.size()) : 0); //Changed!
        Integer last = null; //Changed!

        for (int i = start; i < tds.size(); i++) { //Changed!
            String raw = normalizeCellText(tds.get(i).text()); //Changed!
            Integer v = parsePrisToInt(raw); //Changed!
            if (v != null) last = v; //Changed!
        } //Changed!

        return last != null ? last : 0; //Changed!
    } //Changed!

    private static Integer parsePrisToInt(String raw) { //Changed!
        String t = normalizeCellText(raw); //Changed!
        if (t.isBlank()) return null; //Changed!

        t = t.replaceAll("[()]", "").trim(); //Changed!

        Matcher ma = PRIS_APOSTROPHE.matcher(t); //Changed!
        if (ma.matches()) { //Changed!
            try { return Integer.parseInt(ma.group(1)) * 1000; } catch (NumberFormatException e) { return null; } //Changed!
        } //Changed!

        Matcher mk = PRIS_K.matcher(t); //Changed!
        if (mk.matches()) { //Changed!
            try { return Integer.parseInt(mk.group(1)) * 1000; } catch (NumberFormatException e) { return null; } //Changed!
        } //Changed!

        // Om det finns komma/dot som decimal så är det nästan aldrig "pris" i din vy //Changed!
        if (t.contains(",") && !t.contains(" ")) return null; //Changed!

        Matcher mt = PRIS_THOUSANDS.matcher(t); //Changed!
        if (mt.matches()) { //Changed!
            String digits = mt.group(1).replaceAll("[\\s\\u00A0\\.]", ""); //Changed!
            try { return Integer.parseInt(digits); } catch (NumberFormatException e) { return null; } //Changed!
        } //Changed!

        return null; //Changed!
    } //Changed!

    private static Integer extractOddsFromTds(Elements tds, int distIdx) { //Changed!
        if (tds == null || tds.isEmpty()) return 999; //Changed!

        int timeIdx = (distIdx >= 0 ? distIdx + 1 : -1); //Changed!
        int start = (timeIdx >= 0 ? Math.min(timeIdx + 1, tds.size()) : 0); //Changed!

        Integer last = null; //Changed!
        for (int i = start; i < tds.size(); i++) { //Changed!
            String raw = normalizeCellText(tds.get(i).text()); //Changed!
            if (raw.isBlank()) continue; //Changed!

            // Hoppa sådant som ser ut som pris (t.ex. 30') //Changed!
            if (raw.contains("'") || raw.contains("’")) continue; //Changed!

            Integer v = parseOddsToInt(raw); //Changed!
            if (v != null) last = v; //Changed!
        } //Changed!

        return last != null ? last : 999; //Changed!
    } //Changed!

    private static Integer parseOddsToInt(String raw) { //Changed!
        String t = normalizeCellText(raw).replaceAll("[()]", "").trim(); //Changed!
        if (t.isBlank()) return null; //Changed!

        if (t.codePoints().anyMatch(Character::isLetter)) return null; //Changed!

        Matcher m = ODDS_NUMBER.matcher(t); //Changed!
        if (!m.matches()) return null; //Changed!

        String a = m.group(1); //Changed!
        String b = m.group(2); //Changed!

        try { //Changed!
            BigDecimal val = new BigDecimal(a + "." + (b == null ? "0" : b)); //Changed!
            BigDecimal scaled = val.multiply(BigDecimal.TEN); //Changed!  (x10)
            int rounded = scaled.setScale(0, RoundingMode.HALF_UP).intValue(); //Changed!

            if (rounded < 0 || rounded > 9999) return null; //Changed! (was 999)
            return rounded; //Changed!
        } catch (NumberFormatException e) { //Changed!
            return null; //Changed!
        } //Changed!
    } //Changed!

    private static int findHeaderIndex(Element container, Pattern headerRx) { //Changed!
        if (container == null) return -1; //Changed!
        Element head = container.selectFirst("thead"); //Changed!
        if (head == null) return -1; //Changed!

        Elements hs = head.select("th, td"); //Changed!
        for (int i = 0; i < hs.size(); i++) { //Changed!
            String txt = normalizeCellText(hs.get(i).text()); //Changed!
            if (headerRx.matcher(txt).find()) return i; //Changed!
        } //Changed!
        return -1; //Changed!
    } //Changed!

    private static String textAt(Elements tds, int idx) { //Changed!
        if (tds == null || idx < 0 || idx >= tds.size()) return ""; //Changed!
        return normalizeCellText(tds.get(idx).text()); //Changed!
    } //Changed!

    private ResultHorse buildOrUpdateResultOddsForFuture( //Changed!
                                                         LocalDate date, String bankod, int lap, String horseName, String vOdds //Changed!
    ) { //Changed!
        Integer parsedOdds = parseOddsToInt(vOdds); //Changed!
        if (parsedOdds == null) return null; //Changed!

        int datum = toYyyymmdd(date); //Changed!
        String safeName = normalizeHorseNameSimple(horseName); //Changed!
        if (safeName.isBlank()) return null; //Changed!

        Optional<ResultHorse> existingOpt = resultRepo //Changed!
                .findByDatumAndBankodAndLoppAndNamn(datum, bankod, lap, safeName); //Changed!

        if (existingOpt.isEmpty()) return null; //Changed!  // skapa aldrig ny rad

        ResultHorse rh = existingOpt.get(); //Changed!

        Integer currentOdds = rh.getOdds(); //Changed!
        if (currentOdds == null || currentOdds == 999) { //Changed!
            rh.setOdds(parsedOdds); //Changed!
            return rh; //Changed!
        } //Changed!

        return null; //Changed!
    } //Changed!



}
