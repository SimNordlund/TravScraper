package com.example.travscraper;

import com.example.travscraper.entity.ScrapedHorse;
import com.example.travscraper.repo.ScrapedHorseRepo;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.WaitForSelectorState;
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

import java.text.Normalizer;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class AtgScraperService {

    private final ScraperProperties props;
    private final ScrapedHorseRepo  repo;

    private Playwright playwright;
    private Browser    browser;

    private static final DateTimeFormatter URL_DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @PostConstruct
    void initBrowser() {
        playwright = Playwright.create();
        browser = playwright.chromium()
                .launch(new BrowserType.LaunchOptions().setHeadless(true));
        log.info("🖥️  Headless browser launched");
    }

    @PreDestroy
    void closeBrowser() {
        if (browser != null)    browser.close();
        if (playwright != null) playwright.close();
    }

  /*  @Scheduled(cron = "0 30 22 * * *", zone = "Europe/Stockholm")
    public void scrape() {
        for (LocalDate d = props.getStartDate();
             !d.isAfter(props.getEndDate());
             d = d.plusDays(1)) {

            log.info("📆  Scraping day {}", d);
            LocalDate day = d;
            props.getTracks().forEach(t -> processDateTrack(day, t));
        }
    } */

    @Scheduled(cron = "0 35 22 * * *", zone = "Europe/Stockholm")
    public void scrape() {
        LocalDate target = LocalDate
                .now(ZoneId.of("Europe/Stockholm"))
                .minusDays(1);

        log.info("📆  Scraping {}", target);

        props.getTracks().forEach(t -> processDateTrack(target, t));

    }

    private void processDateTrack(LocalDate date, String track) {

        int consecutiveMisses = 0;
        for (int lap = 1; lap <= 15; lap++) {

            String base = "https://www.atg.se/spel/%s/%s/%s/lopp/%d/resultat";
            String vUrl = String.format(base, date.format(URL_DATE_FORMAT), "vinnare", track, lap);
            String pUrl = String.format(base, date.format(URL_DATE_FORMAT), "plats",   track, lap);
            String tUrl = String.format(base, date.format(URL_DATE_FORMAT), "trio",    track, lap);

            try (BrowserContext ctx = browser.newContext();
                 Page vPage = ctx.newPage();
                 Page pPage = ctx.newPage();
                 Page tPage = ctx.newPage()) {

                vPage.navigate(vUrl);
                pPage.navigate(pUrl);
                tPage.navigate(tUrl);



                if (isCancelledRace(vPage)) {
                    log.info("🔸 Lap {} on {} {} is cancelled, continuing",
                            lap, date, track);
                    if (++consecutiveMisses >= 2) break;
                    continue;
                }


                try {
                    vPage.waitForSelector("tr[data-test-id^=horse-row]",
                            new Page.WaitForSelectorOptions().setTimeout(8_000));
                } catch (PlaywrightException te) {
                    log.info("🔸 No result table for {} {} lap {}, continuing",
                            track, date, lap);
                    if (++consecutiveMisses >= 2) break; //Höj den här vid behov för att scrapa mer lopp.
                    continue;
                }

                /* ─── verify track and lap on every page ─── */
                if (!isCorrectTrack(vPage, track, date)) return;
                if (!isCorrectLap(vPage, lap, track, date) ||
                        !isCorrectLap(pPage, lap, track, date) ||
                        !isCorrectLap(tPage, lap, track, date)) {
                    log.info("🔸 Lap {} missing on {} {}, continuing",
                            lap, date, track);
                    if (++consecutiveMisses >= 2) break;
                    continue;
                }

                consecutiveMisses = 0;

                pPage.waitForSelector("tr[data-test-id^=horse-row]",
                        new Page.WaitForSelectorOptions().setTimeout(8_000));

                tPage.waitForSelector("text=\"Rätt kombination:\"",
                        new Page.WaitForSelectorOptions().setTimeout(5_000)
                                .setState(WaitForSelectorState.ATTACHED));

                Map<String,String> pMap   = extractOddsMap(pPage, "[data-test-id=startlist-cell-podds]");
                Map<String,String> trioMap= extractTrioMap(tPage);

                parseAndPersist(vPage.content(), date, track, lap, pMap, trioMap);
                Thread.sleep(600 + (int)(Math.random()*1200));

            } catch (PlaywrightException e) {
                log.warn("⚠️  Playwright issue on {}", vUrl, e);
                break;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }


    private boolean isCancelledRace(Page page) {
        return Jsoup.parse(page.content())
                .selectFirst("span[class*=cancelledRace], " +
                        "span:matchesOwn(Inställt\\,?\\s+insatser)") != null;
    }


    private boolean isCorrectTrack(Page page, String expected, LocalDate date) {
        Element active = Jsoup.parse(page.content())
                .selectFirst("span[data-test-id^=calendar-menu-track-][data-test-active=true]");
        if (active == null) return true;
        String slug = slugify(active.attr("data-test-id")
                .substring("calendar-menu-track-".length()));
        if (!slug.equals(expected.toLowerCase())) {
            log.info("↪️  {} not present on {} (page shows {}), skipping whole day/track",
                    expected, date, slug);
            return false;
        }
        return true;
    }


    private boolean isCorrectLap(Page page, int expected,
                                 String track, LocalDate date) {

        Document doc = Jsoup.parse(page.content());
        Element sel  = doc.selectFirst("[data-test-selected=true]");
        if (sel == null) return true;

        String current = sel.text().trim();
        if (!current.equals(String.valueOf(expected))) {
            log.info("↪️  lap {} not present on {} {} (page shows {}), "
                    + "skipping rest of laps", expected, date, track, current);
            return false;
        }
        return true;
    }


    private Map<String,String> extractOddsMap(Page page, String oddsSelector) {
        Map<String,String> map = new HashMap<>();
        for (Element tr : Jsoup.parse(page.content())
                .select("tr[data-test-id^=horse-row]")) {
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


    private void parseAndPersist(String html,
                                 LocalDate date, String track, int lap,
                                 Map<String,String> pMap,
                                 Map<String,String> trioMap) {

        Elements rows = Jsoup.parse(html).select("tr[data-test-id^=horse-row]");
        if (rows.isEmpty()) return;

        List<ScrapedHorse> horses = new ArrayList<>();
        for (Element tr : rows) {
            Element place = tr.selectFirst("[data-test-id=horse-placement]");
            Element split = tr.selectFirst("[startlist-export-id^=startlist-cell-horse-split-export]");
            Element vOdd = tr.selectFirst("[data-test-id=startlist-cell-vodds]");
            if (place == null || split == null || vOdd == null) continue;

            String[] parts = split.text().trim().split("\\s+",2);
            String nr   = parts.length>0?parts[0]:"";
            String name = parts.length>1?parts[1]:"";

            String bankode = FULLNAME_TO_BANKODE.getOrDefault(slugify(track), track);

            horses.add(ScrapedHorse.builder()
                    .date(date).track(bankode).lap(String.valueOf(lap))
                    .numberOfHorse(nr).nameOfHorse(name).placement(place.text().trim())
                    .vOdds(vOdd.text().trim())
                    .pOdds(pMap  .getOrDefault(nr,""))
                    .trioOdds(trioMap.getOrDefault(nr,""))
                    .build());
        }

        repo.saveAll(horses);
        log.info("💾 Saved {} horses for {} {} lap {}", horses.size(), date, track, lap);
    }


    private static String slugify(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase();
    }


    private static final Map<String, String> FULLNAME_TO_BANKODE = Map.ofEntries(
            Map.entry("arvika",        "Ar"),
            Map.entry("axevalla",      "Ax"),
            Map.entry("bergsaker",     "B"),
            Map.entry("boden",         "Bo"),
            Map.entry("bollnas",       "Bs"),
            Map.entry("dannero",       "D"),
            Map.entry("dala jarna",    "Dj"),
            Map.entry("eskilstuna",    "E"),
            Map.entry("jagersro",      "J"),
            Map.entry("farjestad",     "F"),
            Map.entry("gavle",         "G"),
            Map.entry("goteborg trav", "Gt"),
            Map.entry("hagmyren",      "H"),
            Map.entry("halmstad",      "Hd"),
            Map.entry("hoting",        "Hg"),
            Map.entry("karlshamn",     "Kh"),
            Map.entry("kalmar",        "Kr"),
            Map.entry("lindesberg",    "L"),
            Map.entry("lycksele",      "Ly"),
            Map.entry("mantorp",       "Mp"),
            Map.entry("oviken",        "Ov"),
            Map.entry("romme",         "Ro"),
            Map.entry("rattvik",       "Rä"),
            Map.entry("solvalla",      "S"),
            Map.entry("skelleftea",    "Sk"),
            Map.entry("solanget",      "Sä"),
            Map.entry("tingsryd",      "Ti"),
            Map.entry("taby trav",     "Tt"),
            Map.entry("umaker",        "U"),
            Map.entry("vemdalen",      "Vd"),
            Map.entry("vaggeryd",      "Vg"),
            Map.entry("visby",         "Vi"),
            Map.entry("aby",           "Å"),
            Map.entry("amal",          "Åm"),
            Map.entry("arjang",        "År"),
            Map.entry("orebro",        "Ö"),
            Map.entry("ostersund",     "Ös")
    );

}
