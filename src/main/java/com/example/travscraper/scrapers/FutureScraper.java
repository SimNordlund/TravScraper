package com.example.travscraper.scrapers;

import com.example.travscraper.ScraperProperties;
import com.example.travscraper.entity.FutureHorse;
import com.example.travscraper.entity.ResultHorse;
import com.example.travscraper.repo.FutureHorseRepo;
import com.example.travscraper.repo.ResultHorseRepo;
import com.example.travscraper.repo.StartListHorseRepo;
import com.microsoft.playwright.*;
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
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.example.travscraper.helpers.TrackHelper.FULLNAME_TO_BANKODE;

@Slf4j
@Service
@RequiredArgsConstructor
public class FutureScraper {

    private static final Pattern BYTE_AV_BANA_TILL = Pattern.compile(
            "Byte\\s+av\\s+bana\\s+till\\s+([^:]+)",
            Pattern.CASE_INSENSITIVE
    );

    private static final DateTimeFormatter URL_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final Pattern ODDS_NUMBER = Pattern.compile("^\\s*(\\d{1,4})(?:[\\.,](\\d{1,2}))?\\s*$");

    private static final Map<String, String> BANKODE_TO_SLUG;

    static {
        Map<String, String> m = new HashMap<>();
        FULLNAME_TO_BANKODE.forEach((slug, code) -> m.put(code, slug));
        BANKODE_TO_SLUG = Collections.unmodifiableMap(m);
    }

    private final ScraperProperties props;
    private final FutureHorseRepo futureRepo;
    private final ResultHorseRepo resultRepo;
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
        log.info("🖥️  Headless browser launched (FutureScraper)");
    }

    @PreDestroy
    void closeBrowser() {
        if (ctx != null) ctx.close();
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
    }

    // @Scheduled(cron = "0 55 23 * * *", zone = "Europe/Stockholm")
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

            // exakt samma “hardcoded tracks” som du hade
            List<String> hardcodedTracks = List.of(
                    "bjerke",
                    "orkla",
                    "bodo",
                    "biri",
                    "bergen",
                    "drammen",
                    "forus",
                    "harstad",
                    "haugaland",
                    "jarlsberg",
                    "klosterskogen",
                    "leangen",
                    "momarken",
                    "sorlandet",
                    "arhus",
                    "billund",
                    "bornholm",
                    "charlottenlund",
                    "nykobing",
                    "odense",
                    "skive",
                    "alborg",
                    "mariehamn"
            );

            for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
                log.info("📆  Scraping FUTURE {}", date);

                // refaktor: bygg en set så vi inte kör samma track två gånger om den råkar finnas i båda
                Set<String> allTracks = new LinkedHashSet<>();
                allTracks.addAll(tracksFor(date));
                allTracks.addAll(hardcodedTracks);

                for (String track : allTracks) {
                    processDateTrackFuture(date, track);
                }
            }
        } finally {
            lock.unlock();
        }
    }

    // ---------- core flow ----------

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

    private void processDateTrackFuture(LocalDate date, String track) {
        ensureContext();

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

                String effectiveTrack = resolveEffectiveTrackSlug(page, track);

                if (!isCorrectTrack(page, effectiveTrack, date)) return;
                if (!isCorrectLap(page, lap, effectiveTrack, date)) {
                    log.info("🔸 Lap {} missing on {} {} (future), continuing", lap, date, effectiveTrack);
                    if (++consecutiveMisses >= 2) break;
                    continue;
                }

                consecutiveMisses = 0;

                parseAndPersistFuture(page.content(), date, effectiveTrack, lap);

                try {
                    Thread.sleep(600 + (int) (Math.random() * 1200));
                } catch (InterruptedException ignored) {
                }

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

    // ---------- parsing + persistence ----------

    private void parseAndPersistFuture(String html, LocalDate date, String track, int lap) {
        Elements rows = Jsoup.parse(html).select("tr[data-test-id^=horse-row]");
        if (rows.isEmpty()) return;

        List<FutureHorse> toSave = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<ResultHorse> oddsUpdates = new ArrayList<>();

        List<String> track1337 = List.of(
                "bjerke",
                "orkla",
                "bodo",
                "biri",
                "bergen",
                "drammen",
                "forus",
                "harstad",
                "haugaland",
                "jarlsberg",
                "klosterskogen",
                "leangen",
                "momarken",
                "sorlandet",
                "arhus",
                "billund",
                "bornholm",
                "charlottenlund",
                "nykobing",
                "odense",
                "skive",
                "alborg",
                "mariehamn"
        );

        for (Element tr : rows) {
            String nr = extractStartNumber(tr).replaceAll("\\D+", "");
            if (nr.isBlank()) continue;
            if (!seen.add(nr)) continue;

            Element split = tr.selectFirst("[startlist-export-id^=startlist-cell-horse-split-export]");
            String rawName = (split != null) ? normalizeCellText(split.text()) : "";
            if (rawName.isBlank()) rawName = normalizeCellText(tr.text());

            rawName = rawName.replaceFirst("^\\s*" + Pattern.quote(nr) + "\\s+", "");
            rawName = rawName.replaceFirst("^\\s*\\d{1,2}\\s+", "");

            String normalizedName = normalizeHorseNameSimple(rawName);
            if (normalizedName.isBlank()) continue;

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
                fh.setNameOfHorse(normalizedName);
                fh.setVOdds(vOdds);
            } else {
                fh = FutureHorse.builder()
                        .date(date)
                        .track(bankode)
                        .lap(String.valueOf(lap))
                        .numberOfHorse(nr)
                        .nameOfHorse(normalizedName)
                        .vOdds(vOdds)
                        .build();
            }

            toSave.add(fh);

            boolean allowCreateResultRow = track1337.contains(trackKey(track))
                    || track1337.contains(trackKey(BANKODE_TO_SLUG.getOrDefault(bankode, "")));

            ResultHorse rhOdds = buildOrUpdateResultOddsForFuture(
                    date, bankode, lap, normalizedName, nr, vOdds, allowCreateResultRow
            );
            if (rhOdds != null) oddsUpdates.add(rhOdds);
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

        if (!oddsUpdates.isEmpty()) {
            try {
                resultRepo.saveAll(oddsUpdates);
                log.info("💾 (future) Uppdaterade/skapade {} odds-rader i RESULTAT för {} {} lap {}", oddsUpdates.size(), date, track, lap);
            } catch (DataIntegrityViolationException dive) {
                log.warn("🔁 (future) saveAll odds krockade, retrying per row på {} {} lap {}: {}",
                        date, track, lap, dive.getMostSpecificCause().getMessage());

                for (ResultHorse rh : oddsUpdates) {
                    try {
                        resultRepo.save(rh);
                    } catch (DataIntegrityViolationException ignored) {
                        try {
                            Optional<ResultHorse> existing = resultRepo
                                    .findByDatumAndBankodAndLoppAndNamn(rh.getDatum(), rh.getBankod(), rh.getLopp(), rh.getNamn());
                            if (existing.isPresent()) {
                                ResultHorse e = existing.get();
                                Integer currentOdds = e.getOdds();
                                boolean isEjOdds = (rh.getOdds() != null && rh.getOdds() == 99);

                                if (isEjOdds) {
                                    if (currentOdds == null || currentOdds != 99) {
                                        e.setOdds(99);
                                        resultRepo.save(e);
                                    }
                                } else if (currentOdds == null || currentOdds == 999) {
                                    e.setOdds(rh.getOdds());
                                    resultRepo.save(e);
                                }
                            }
                        } catch (Exception ex) {
                            log.warn("⚠️  (future) Kunde inte retry-upserta odds datum={} bankod={} lopp={} namn={} ({})",
                                    rh.getDatum(), rh.getBankod(), rh.getLopp(), rh.getNamn(), ex.getMessage());
                        }
                    }
                }
            }
        }

        log.info("💾 (future) Saved/updated {} horses for {} {} lap {}", toSave.size(), date, track, lap);
    }

    // ---------- helpers ----------

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

    private String extractStartNumber(Element tr) {
        Element numBtn = tr.selectFirst("button[data-test-start-number], [data-test-start-number]");
        if (numBtn != null) {
            String n = numBtn.attr("data-test-start-number");
            if (n != null && !n.isBlank()) return n.trim();
        }

        Element split = tr.selectFirst("[startlist-export-id^=startlist-cell-horse-split-export]");
        if (split != null) {
            for (Element sp : split.select("span")) {
                String d = normalizeCellText(sp.text()).replaceAll("\\D+", "");
                if (!d.isBlank()) return d;
            }

            String splitTxt = normalizeCellText(split.text());
            Matcher m = Pattern.compile("^\\s*(\\d{1,2})\\b").matcher(splitTxt);
            if (m.find()) return m.group(1);
        }

        Element nrText = tr.selectFirst("[data-test-id=horse-start-number], [class*=startNumber]");
        if (nrText != null) {
            String n = nrText.text().replaceAll("\\D+", "");
            if (!n.isBlank()) return n;
        }
        return "";
    }

    private String extractTrackSwitchTargetFromAlert(Page page) {
        try {
            Locator strong = page.locator("[data-test-id=\"game-alerts\"] strong")
                    .filter(new Locator.FilterOptions().setHasText(
                            Pattern.compile("Byte\\s+av\\s+bana\\s+till", Pattern.CASE_INSENSITIVE)));

            if (strong.count() == 0) return null;

            String txt = normalizeCellText(strong.first().innerText());
            Matcher m = BYTE_AV_BANA_TILL.matcher(txt);
            if (!m.find()) return null;

            String target = normalizeCellText(m.group(1));
            return target.isBlank() ? null : target;
        } catch (PlaywrightException ignored) {
            return null;
        }
    }

    private String resolveEffectiveTrackSlug(Page page, String requestedTrackSlug) {
        String targetName = extractTrackSwitchTargetFromAlert(page);
        if (targetName == null) return requestedTrackSlug;

        String bankod = toKnownBankodOrNull(targetName);
        if (bankod == null) {
            log.warn("⚠️  Byte av bana hittades men okänd bana '{}' på {}, behåller '{}'",
                    targetName, page.url(), requestedTrackSlug);
            return requestedTrackSlug;
        }

        String overrideSlug = BANKODE_TO_SLUG.getOrDefault(bankod, trackKey(targetName));

        if (!trackKey(requestedTrackSlug).equals(trackKey(overrideSlug))) {
            log.info("🔁 Byte av bana på {}: '{}' -> '{}' ({} -> {})",
                    page.url(),
                    requestedTrackSlug,
                    overrideSlug,
                    toKnownBankodOrNull(requestedTrackSlug),
                    bankod);
        }

        return overrideSlug;
    }

    private ResultHorse buildOrUpdateResultOddsForFuture(
            LocalDate date,
            String bankod,
            int lap,
            String horseName,
            String startNumber,
            String vOdds,
            boolean allowCreateIfMissing
    ) {
        String vOddsNorm = normalizeCellText(vOdds);
        String vOddsUpper = vOddsNorm.toUpperCase(Locale.ROOT).replace(".", "").trim();
        boolean isEj = "EJ".equals(vOddsUpper);

        Integer parsedOdds = isEj ? 99 : parseOddsToInt(vOddsNorm);
        if (parsedOdds == null) return null;

        int datum = toYyyymmdd(date);
        String safeName = normalizeHorseNameSimple(horseName);
        if (safeName.isBlank()) return null;

        Optional<ResultHorse> existingOpt = resultRepo
                .findByDatumAndBankodAndLoppAndNamn(datum, bankod, lap, safeName);

        if (existingOpt.isPresent()) {
            ResultHorse rh = existingOpt.get();
            Integer currentOdds = rh.getOdds();

            if (isEj) {
                if (currentOdds == null || currentOdds != 99) {
                    rh.setOdds(99);
                    return rh;
                }
                return null;
            }

            if (currentOdds == null || currentOdds == 999) {
                rh.setOdds(parsedOdds);
                return rh;
            }

            return null;
        }

        if (!allowCreateIfMissing) return null;

        int parsedNr = 0;
        if (startNumber != null) {
            String digits = startNumber.replaceAll("\\D+", "");
            if (!digits.isBlank()) {
                try {
                    parsedNr = Integer.parseInt(digits);
                } catch (NumberFormatException ignored) {
                    parsedNr = 0;
                }
            }
        }

        return ResultHorse.builder()
                .datum(datum)
                .bankod(bankod)
                .lopp(lap)
                .nr(parsedNr)
                .namn(safeName)
                .odds(parsedOdds)
                .build();
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

    private static Integer parseOddsToInt(String raw) {
        String t = normalizeCellText(raw).replaceAll("[()]", "").trim();
        if (t.isBlank()) return null;

        if (t.codePoints().anyMatch(Character::isLetter)) return null;

        Matcher m = ODDS_NUMBER.matcher(t);
        if (!m.matches()) return null;

        String a = m.group(1);
        String b = m.group(2);

        try {
            BigDecimal val = new BigDecimal(a + "." + (b == null ? "0" : b));
            BigDecimal scaled = val.multiply(BigDecimal.TEN);
            int rounded = scaled.setScale(0, RoundingMode.HALF_UP).intValue();

            if (rounded < 0 || rounded > 9999) return null;
            return rounded;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
