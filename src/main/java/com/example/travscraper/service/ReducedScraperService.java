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
public class ReducedScraperService {

    private static final ZoneId STOCKHOLM = ZoneId.of("Europe/Stockholm");
    private static final DateTimeFormatter URL_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final String GAME_TYPE_V86 = "V86";
    private static final String GAME_TYPE_V85 = "V85";
    private static final String GAME_TYPE_V64 = "V64";
    private static final String GAME_TYPE_V65 = "V65";
    private static final String GAME_TYPE_GS75 = "GS75";
    private static final int MAX_DEPARTMENTS = 15;
    private static final Pattern STRECK_VALUE = Pattern.compile("(<)?\\s*(\\d{1,3})(?:[\\.,](\\d{1,2}))?\\s*%?");
    private static final String SEL_COOKIE_BUTTONS =
            "button:has-text(\"Tillåt alla\"):visible, " +
                    "button:has-text(\"Avvisa\"):visible, " +
                    "button:has-text(\"Godkänn alla cookies\"):visible, " +
                    "button:has-text(\"Jag förstår\"):visible";
    private static final Pattern GAME_ROUTE_PATTERN = Pattern.compile(
            "^https?://www\\.atg\\.se/spel/(\\d{4}-\\d{2}-\\d{2})/([^/]+)/([^/]+)/avd/(\\d+)(?:[/?#].*)?$",
            Pattern.CASE_INSENSITIVE
    );

    private final ScraperProperties props;
    private final ReducedSystemRepo reducedSystemRepo;
    private final ReentrantLock lock = new ReentrantLock();

    private Playwright playwright;
    private Browser browser;
    private BrowserContext ctx;

    public void scrapeV86() {
        scrapeGame(GAME_TYPE_V86);
    }

    public void scrapeV85() {
        scrapeGame(GAME_TYPE_V85);
    }

    public void scrapeV64() {
        scrapeGame(GAME_TYPE_V64);
    }

    public void scrapeV65() {
        scrapeGame(GAME_TYPE_V65);
    }

    public void scrapeGS75() {
        scrapeGame(GAME_TYPE_GS75);
    }

    private void scrapeGame(String gameType) {
        if (!lock.tryLock()) {
            log.warn("Previous reduced-system scrape still running - skipping");
            return;
        }
        try {
            LocalDate end = Optional.ofNullable(props.getEndDateReducedSystem())
                    .orElse(LocalDate.now(STOCKHOLM));
            LocalDate start = Optional.ofNullable(props.getStartDateReducedSystem())
                    .orElse(end);

            String gameTypeUrl = gameType.toUpperCase(Locale.ROOT);
            for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
                log.info("ReducedSystem: scanning {} for {}", date, gameTypeUrl);
                List<GameTarget> targets = findGameTargetsOnCalendar(date, gameTypeUrl);
                for (GameTarget target : targets) {
                    scrapeGameTarget(target);
                }
            }
        } finally {
            lock.unlock();
        }
    }

    private List<GameTarget> findGameTargetsOnCalendar(LocalDate date, String gameTypeUrl) {
        ensureBrowser();

        String dateSlug = date.format(URL_DATE_FORMAT);
        String url = "https://www.atg.se/spel/kalender/" + dateSlug;

        try (Page page = ctx.newPage()) {
            page.navigate(url, navOptions());
            dismissCookiesIfPresent(page);

            try {
                page.waitForSelector("table[aria-label='Spelkalender'], a[href*='/spel/']",
                        new Page.WaitForSelectorOptions().setTimeout(30_000));
            } catch (PlaywrightException ignored) {
            }

            List<GameTarget> targets = parseCalendarTargets(page.content(), date, gameTypeUrl);
            if (targets.isEmpty()) {
                log.info("ReducedSystem: no {} found on {}", gameTypeUrl, date);
            } else {
                log.info("ReducedSystem: found {} {} target(s) on {}", targets.size(), gameTypeUrl, date);
            }
            return targets;
        } catch (PlaywrightException e) {
            log.warn("ReducedSystem: calendar scrape failed for {} {}: {}", gameTypeUrl, date, e.getMessage());
            return List.of();
        }
    }

    private List<GameTarget> parseCalendarTargets(String html, LocalDate date, String gameTypeUrl) {
        String dateSlug = date.format(URL_DATE_FORMAT);
        Pattern gameLinkPattern = Pattern.compile(
                "/spel/" + Pattern.quote(dateSlug) + "/" + Pattern.quote(gameTypeUrl) + "/([^/?#]+)",
                Pattern.CASE_INSENSITIVE
        );

        Document doc = Jsoup.parse(html);
        Map<String, GameTarget> targetsByTrack = new LinkedHashMap<>();

        for (Element link : doc.select("a[href]")) {
            String href = link.attr("href");
            Matcher matcher = gameLinkPattern.matcher(href);
            if (!matcher.find()) continue;

            String trackSlug = matcher.group(1).trim();
            String bankod = toKnownBankodOrNull(trackSlug);
            if (bankod == null) {
                Element row = link.closest("tr");
                Element trackName = row != null ? row.selectFirst("[data-test-id=track-name]") : null;
                if (trackName != null) {
                    bankod = toKnownBankodOrNull(trackName.text());
                }
            }

            if (bankod == null) {
                log.warn("ReducedSystem: unknown track '{}' for {} on calendar {}, skipping", trackSlug, gameTypeUrl, date);
                continue;
            }

            String key = gameTypeUrl + "|" + trackKey(trackSlug);
            targetsByTrack.putIfAbsent(key, new GameTarget(date, gameTypeUrl, gameTypeUrl.toLowerCase(Locale.ROOT), trackSlug, bankod));
        }

        return new ArrayList<>(targetsByTrack.values());
    }

    private void scrapeGameTarget(GameTarget target) {
        int misses = 0;
        for (int avd = 1; avd <= MAX_DEPARTMENTS; avd++) {
            boolean scraped = scrapeDepartment(target, avd);
            if (scraped) {
                misses = 0;
            } else if (++misses >= 2) {
                break;
            }
        }
    }

    private boolean scrapeDepartment(GameTarget target, int avd) {
        ensureBrowser();

        String url = String.format("https://www.atg.se/spel/%s/%s/%s/avd/%d",
                target.date().format(URL_DATE_FORMAT), target.gameTypeUrl(), target.trackSlug(), avd);

        try (Page page = ctx.newPage()) {
            page.navigate(url, navOptions());

            if (page.url().contains("/spel/kalender/")) {
                log.info("ReducedSystem: {} {} {} avd {} redirected to calendar, stopping/continuing misses",
                        target.date(), target.gameTypeUrl(), target.trackSlug(), avd);
                return false;
            }

            GameRoute route = parseGameRoute(page.url());
            if (route != null && !route.matches(target, avd)) {
                log.info("ReducedSystem: URL mismatch for {} {} {} avd {} -> landed at {}, skipping",
                        target.date(), target.gameTypeUrl(), target.trackSlug(), avd, page.url());
                return false;
            }

            ElementHandle first = page.waitForSelector(
                    SEL_COOKIE_BUTTONS + ", tr[data-test-id^=horse-row]",
                    new Page.WaitForSelectorOptions().setTimeout(60_000));

            if (first != null && "BUTTON".equalsIgnoreCase(String.valueOf(first.evaluate("e => e.tagName")))) {
                first.click();
                page.waitForSelector("tr[data-test-id^=horse-row]",
                        new Page.WaitForSelectorOptions().setTimeout(60_000));
            }

            int saved = parseAndPersistDepartment(page.content(), target, avd);
            if (saved <= 0) {
                log.info("ReducedSystem: no rows saved for {} {} {} avd {}", target.date(), target.gameTypeUrl(), target.trackSlug(), avd);
                return false;
            }

            log.info("ReducedSystem: saved/updated {} rows for {} {} {} avd {}",
                    saved, target.date(), target.gameTypeUrl(), target.trackSlug(), avd);
            return true;
        } catch (PlaywrightException e) {
            log.warn("ReducedSystem: Playwright error on {}: {}", url, e.getMessage());
            return false;
        }
    }

    private int parseAndPersistDepartment(String html, GameTarget target, int avd) {
        Document doc = Jsoup.parse(html);
        List<Element> rows = doc.select("tr[data-test-id^=horse-row]");
        if (rows.isEmpty()) return 0;

        String startDatum = target.date().format(URL_DATE_FORMAT);
        Map<Integer, ReducedSystem> existingByNr = new HashMap<>();
        for (ReducedSystem existing : reducedSystemRepo.findByStartDatumAndBanKodAndStreckTypAndLopp(
                startDatum, target.bankod(), target.streckTyp(), avd)) {
            existingByNr.put(existing.getNr(), existing);
        }

        List<ReducedSystem> toSave = new ArrayList<>();
        for (Element row : rows) {
            Integer nr = extractStartNumber(row);
            BigDecimal streck = extractStreck(row);
            if (nr == null || streck == null) continue;

            ReducedSystem reduced = existingByNr.get(nr);
            if (reduced == null) {
                reduced = ReducedSystem.builder()
                        .startDatum(startDatum)
                        .banKod(target.bankod())
                        .streckTyp(target.streckTyp())
                        .lopp(avd)
                        .nr(nr)
                        .build();
            }

            reduced.setStreck(streck);
            reduced.setNr(nr);
            reduced.setLopp(avd);
            reduced.setBanKod(target.bankod());
            reduced.setStartDatum(startDatum);
            reduced.setStreckTyp(target.streckTyp());
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

    private BigDecimal extractStreck(Element row) {
        Element el = row.selectFirst("[data-test-id=startlist-cell-bet-distribution]");
        if (el == null) {
            el = row.selectFirst("[data-test-id=startlist-cell-vodds]");
        }
        if (el == null) {
            el = row.selectFirst("[startlist-export-id=startlist-cell-defaultOdds-export]");
        }
        if (el == null) return null;

        String text = normalizeCellText(el.text()).replace('\u00A0', ' ');
        Matcher matcher = STRECK_VALUE.matcher(text);
        if (!matcher.find()) return null;

        if (matcher.group(1) != null) return BigDecimal.ZERO;

        String decimalPart = matcher.group(3);
        String normalized = matcher.group(2) + (decimalPart == null ? "" : "." + decimalPart);
        return new BigDecimal(normalized);
    }

    private GameRoute parseGameRoute(String url) {
        if (url == null || url.isBlank()) return null;
        Matcher matcher = GAME_ROUTE_PATTERN.matcher(url);
        if (!matcher.find()) return null;
        try {
            return new GameRoute(
                    LocalDate.parse(matcher.group(1), URL_DATE_FORMAT),
                    matcher.group(2),
                    matcher.group(3),
                    Integer.parseInt(matcher.group(4))
            );
        } catch (Exception ignored) {
            return null;
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

        log.info("ReducedSystem: headless browser launched");
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

    private record GameTarget(LocalDate date, String gameTypeUrl, String streckTyp, String trackSlug, String bankod) {
    }

    private record GameRoute(LocalDate date, String gameTypeUrl, String trackSlug, int avd) {
        boolean matches(GameTarget target, int expectedAvd) {
            return date.equals(target.date())
                    && gameTypeUrl.equalsIgnoreCase(target.gameTypeUrl())
                    && trackKey(trackSlug).equals(trackKey(target.trackSlug()))
                    && avd == expectedAvd;
        }
    }
}

