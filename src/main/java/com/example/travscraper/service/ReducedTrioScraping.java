package com.example.travscraper.service;

import com.example.travscraper.ScraperProperties;
import com.example.travscraper.entity.ReducedSystem;
import com.example.travscraper.repo.ReducedSystemRepo;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.WaitUntilState;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
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
public class ReducedTrioScraping {

    private static final ZoneId STOCKHOLM = ZoneId.of("Europe/Stockholm");
    private static final DateTimeFormatter URL_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final String STRECK_TYP_TRIO = "trio";
    private static final int MAX_LOPP = 15;
    private static final int CALENDAR_READY_TIMEOUT_MS = 10_000;
    private static final int TRIO_READY_TIMEOUT_MS = 20_000;
    private static final Pattern TRIO_VALUE = Pattern.compile("(<)?\\s*(\\d{1,4})(?:[\\.,](\\d{1,2}))?\\s*%?");
    private static final Pattern TRIO_ROUTE_PATTERN = Pattern.compile(
            "^https?://www\\.atg\\.se/spel/(\\d{4}-\\d{2}-\\d{2})/trio/([^/]+)/lopp/(\\d+)(?:[/?#].*)?$",
            Pattern.CASE_INSENSITIVE
    );
    private static final String SEL_COOKIE_BUTTONS =
            "button:has-text(\"Tillåt alla\"):visible, " +
                    "button:has-text(\"Avvisa\"):visible, " +
                    "button:has-text(\"Godkänn alla cookies\"):visible, " +
                    "button:has-text(\"Jag förstår\"):visible";
    private static final Set<String> ALLOWED_BANKODER = Set.of(
            "S", "Å", "J", "Ax", "B", "Bo", "Bs", "D", "E", "F", "G", "H", "Hd", "Kr", "L", "Mp", "Ro",
            "Rä", "Sk", "Sä", "U", "Vi", "Åm", "År", "Ö", "Ös", "Ho", "Hg", "Vg", "Ti"
    );

    private final ScraperProperties props;
    private final ReducedSystemRepo reducedSystemRepo;
    private final ReentrantLock lock = new ReentrantLock();

    private Playwright playwright;
    private Browser browser;
    private BrowserContext ctx;

    public void scrapeTrio() {
        if (!lock.tryLock()) {
            log.warn("Previous reduced trio scrape still running - skipping");
            return;
        }
        try {
            LocalDate end = Optional.ofNullable(props.getEndDateReducedSystem())
                    .orElse(LocalDate.now(STOCKHOLM));
            LocalDate start = Optional.ofNullable(props.getStartDateReducedSystem())
                    .orElse(end);

            for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
                log.info("ReducedSystem Trio: scanning calendar {}", date);
                List<TrioTarget> targets = findCalendarTracks(date);
                if (targets.isEmpty()) {
                    log.info("ReducedSystem Trio: no allowed tracks found on {}", date);
                    continue;
                }

                for (TrioTarget target : targets) {
                    scrapeTrack(target);
                }
            }
        } finally {
            lock.unlock();
        }
    }

    private List<TrioTarget> findCalendarTracks(LocalDate date) {
        ensureBrowser();

        String dateSlug = date.format(URL_DATE_FORMAT);
        String url = "https://www.atg.se/spel/kalender/" + dateSlug;

        try (Page page = ctx.newPage()) {
            page.navigate(url, navOptions());
            dismissCookiesIfPresent(page);
            waitForCalendarReady(page);

            Document doc = Jsoup.parse(page.content());
            Pattern trackLinkPattern = Pattern.compile(
                    "/spel/" + Pattern.quote(dateSlug) + "/vinnare/([^/?#]+)/lopp/\\d+",
                    Pattern.CASE_INSENSITIVE
            );
            Map<String, TrioTarget> targetsByTrack = new LinkedHashMap<>();

            for (Element link : doc.select("a[href]")) {
                String href = link.attr("href");
                Matcher matcher = trackLinkPattern.matcher(href);
                if (!matcher.find()) continue;

                String trackSlug = matcher.group(1).trim();
                String bankod = resolveBankod(trackSlug, link);
                if (bankod == null || !ALLOWED_BANKODER.contains(bankod)) continue;

                String key = trackKey(trackSlug);
                targetsByTrack.putIfAbsent(key, new TrioTarget(date, trackSlug, bankod));
            }

            List<TrioTarget> targets = new ArrayList<>(targetsByTrack.values());
            log.info("ReducedSystem Trio: found {} allowed track(s) on {}", targets.size(), date);
            return targets;
        } catch (PlaywrightException e) {
            log.warn("ReducedSystem Trio: calendar scrape failed for {}: {}", date, e.getMessage());
            return List.of();
        }
    }

    private String resolveBankod(String trackSlug, Element link) {
        String bankod = toKnownBankodOrNull(trackSlug);
        if (bankod != null) return bankod;

        Element row = link.closest("tr");
        Element trackName = row != null ? row.selectFirst("[data-test-id=track-name]") : null;
        if (trackName == null) return null;
        return toKnownBankodOrNull(trackName.text());
    }

    private void scrapeTrack(TrioTarget target) {
        int misses = 0;
        for (int lopp = 1; lopp <= MAX_LOPP; lopp++) {
            boolean scraped = scrapeLopp(target, lopp);
            if (scraped) {
                misses = 0;
            } else if (++misses >= 2) {
                break;
            }
        }
    }

    private boolean scrapeLopp(TrioTarget target, int lopp) {
        ensureBrowser();

        String url = String.format("https://www.atg.se/spel/%s/trio/%s/lopp/%d",
                target.date().format(URL_DATE_FORMAT), target.trackSlug(), lopp);

        try (Page page = ctx.newPage()) {
            page.navigate(url, navOptions());

            if (page.url().contains("/spel/kalender/")) {
                log.info("ReducedSystem Trio: {} {} lopp {} redirected to calendar", target.date(), target.trackSlug(), lopp);
                return false;
            }

            TrioRoute route = parseTrioRoute(page.url());
            if (route != null && !route.matches(target, lopp)) {
                log.info("ReducedSystem Trio: URL mismatch for {} {} lopp {} -> landed at {}, skipping",
                        target.date(), target.trackSlug(), lopp, page.url());
                return false;
            }

            ElementHandle first = page.waitForSelector(
                    SEL_COOKIE_BUTTONS + ", tr[data-test-id^=horse-row]",
                    new Page.WaitForSelectorOptions().setTimeout(TRIO_READY_TIMEOUT_MS));

            if (first != null && "BUTTON".equalsIgnoreCase(String.valueOf(first.evaluate("e => e.tagName")))) {
                first.click();
                page.waitForSelector("tr[data-test-id^=horse-row]",
                        new Page.WaitForSelectorOptions().setTimeout(TRIO_READY_TIMEOUT_MS));
            }

            int saved = parseAndPersistLopp(page.content(), target, lopp);
            if (saved <= 0) {
                log.info("ReducedSystem Trio: no rows saved for {} {} lopp {}", target.date(), target.trackSlug(), lopp);
                return false;
            }

            log.info("ReducedSystem Trio: saved/updated {} rows for {} {} lopp {}",
                    saved, target.date(), target.trackSlug(), lopp);
            return true;
        } catch (PlaywrightException e) {
            String message = e.getMessage();
            if (message != null && message.contains("Timeout")) {
                log.info("ReducedSystem Trio: no horse rows found on {}, skipping", url);
            } else {
                log.warn("ReducedSystem Trio: Playwright error on {}: {}", url, message);
            }
            return false;
        }
    }

    private int parseAndPersistLopp(String html, TrioTarget target, int lopp) {
        Document doc = Jsoup.parse(html);
        List<Element> rows = doc.select("tr[data-test-id^=horse-row]");
        if (rows.isEmpty()) return 0;

        String startDatum = target.date().format(URL_DATE_FORMAT);
        Map<Integer, ReducedSystem> existingByNr = new HashMap<>();
        for (ReducedSystem existing : reducedSystemRepo.findByStartDatumAndBanKodAndStreckTypAndLopp(
                startDatum, target.bankod(), STRECK_TYP_TRIO, lopp)) {
            existingByNr.put(existing.getNr(), existing);
        }

        List<ReducedSystem> toSave = new ArrayList<>();
        for (Element row : rows) {
            Integer nr = extractStartNumber(row);
            BigDecimal streck = extractTrioIndex(row);
            if (nr == null || streck == null) continue;

            ReducedSystem reduced = existingByNr.get(nr);
            if (reduced == null) {
                reduced = ReducedSystem.builder()
                        .startDatum(startDatum)
                        .banKod(target.bankod())
                        .streckTyp(STRECK_TYP_TRIO)
                        .lopp(lopp)
                        .nr(nr)
                        .build();
            }

            reduced.setStreck(streck);
            reduced.setNr(nr);
            reduced.setLopp(lopp);
            reduced.setBanKod(target.bankod());
            reduced.setStartDatum(startDatum);
            reduced.setStreckTyp(STRECK_TYP_TRIO);
            toSave.add(reduced);
        }

        if (toSave.isEmpty()) return 0;
        reducedSystemRepo.saveAll(toSave);
        return toSave.size();
    }

    private Integer extractStartNumber(Element row) {
        Element startNumber = row.selectFirst("button[data-test-start-number], [data-test-start-number]");
        if (startNumber != null) {
            String attr = startNumber.attr("data-test-start-number").replaceAll("\\D+", "");
            if (!attr.isBlank()) return Integer.parseInt(attr);
        }

        Element split = row.selectFirst("[startlist-export-id^=startlist-cell-horse-split-export]");
        if (split != null) {
            Matcher matcher = Pattern.compile("^\\s*(\\d{1,2})\\b").matcher(normalizeCellText(split.text()));
            if (matcher.find()) return Integer.parseInt(matcher.group(1));
        }

        return null;
    }

    private BigDecimal extractTrioIndex(Element row) {
        Element el = row.selectFirst("[data-test-id=startlist-cell-trioindex]");
        if (el == null) return null;

        String text = normalizeCellText(el.text()).replace('\u00A0', ' ');
        Matcher matcher = TRIO_VALUE.matcher(text);
        if (!matcher.find()) return null;

        if (matcher.group(1) != null) return BigDecimal.ZERO;

        String decimalPart = matcher.group(3);
        String normalized = matcher.group(2) + (decimalPart == null ? "" : "." + decimalPart);
        return new BigDecimal(normalized);
    }

    private TrioRoute parseTrioRoute(String url) {
        if (url == null || url.isBlank()) return null;
        Matcher matcher = TRIO_ROUTE_PATTERN.matcher(url);
        if (!matcher.find()) return null;
        try {
            return new TrioRoute(
                    LocalDate.parse(matcher.group(1), URL_DATE_FORMAT),
                    matcher.group(2),
                    Integer.parseInt(matcher.group(3))
            );
        } catch (Exception ignored) {
            return null;
        }
    }

    private void waitForCalendarReady(Page page) {
        try {
            page.waitForSelector(
                    "[data-test-id=calendar-section], table[aria-label=Spelkalender], a[href*=\"/spel/\"]",
                    new Page.WaitForSelectorOptions().setTimeout(CALENDAR_READY_TIMEOUT_MS));
        } catch (PlaywrightException ignored) {
        }
    }

    private Page.NavigateOptions navOptions() {
        return new Page.NavigateOptions()
                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                .setTimeout(75_000);
    }

    private void ensureBrowser() {
        if (playwright != null && browser != null && ctx != null) return;

        playwright = Playwright.create();
        browser = playwright.chromium().launch(
                new BrowserType.LaunchOptions()
                        .setHeadless(true)
                        .setArgs(List.of("--disable-blink-features=AutomationControlled"))
        );

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

        ctx.route("**/*", route -> {
            String resourceType = route.request().resourceType();
            if ("image".equals(resourceType) || "media".equals(resourceType) || "font".equals(resourceType)) {
                route.abort();
            } else {
                route.resume();
            }
        });

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

        log.info("ReducedSystem Trio: headless browser launched");
    }

    private void dismissCookiesIfPresent(Page page) {
        try {
            Locator cookie = page.locator(SEL_COOKIE_BUTTONS);
            if (cookie.count() > 0) {
                cookie.first().click(new Locator.ClickOptions().setTimeout(10_000).setForce(true));
            }
        } catch (PlaywrightException ignored) {
        }
    }

    @PreDestroy
    void closeBrowser() {
        if (ctx != null) ctx.close();
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
    }

    private static String normalizeCellText(String s) {
        if (s == null) return "";
        return s.replace('\u00A0', ' ').replaceAll("\\s+", " ").trim();
    }

    private static String slugify(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT);
    }

    private static String trackKey(String trackLike) {
        if (trackLike == null) return "";
        String key = slugify(trackLike);
        key = key.replace('-', ' ').replace('_', ' ');
        return key.replaceAll("\\s+", " ").trim();
    }

    private static String toKnownBankodOrNull(String trackLike) {
        String key = trackKey(trackLike);
        if (key.isBlank()) return null;
        return FULLNAME_TO_BANKODE.get(key);
    }

    private record TrioTarget(LocalDate date, String trackSlug, String bankod) {
    }

    private record TrioRoute(LocalDate date, String trackSlug, int lopp) {
        boolean matches(TrioTarget target, int expectedLopp) {
            return date.equals(target.date())
                    && trackKey(trackSlug).equals(trackKey(target.trackSlug()))
                    && lopp == expectedLopp;
        }
    }
}
