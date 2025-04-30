package com.example.travscraper;

import com.example.travscraper.entity.ScrapedHorse;
import com.example.travscraper.repo.ScrapedHorseRepo;
import com.microsoft.playwright.*;
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
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class AtgScraperService {

    private final ScraperProperties props;
    private final ScrapedHorseRepo repo;

    private Playwright playwright;
    private Browser browser;

    private static final DateTimeFormatter URL_DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /* ---------------- life-cycle ---------------- */
    @PostConstruct
    void initBrowser() {
        playwright = Playwright.create();
        browser = playwright.chromium()
                .launch(new BrowserType.LaunchOptions().setHeadless(true));
        log.info("🖥️  Headless browser launched");
    }

    @PreDestroy
    void closeBrowser() {
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
    }

    /* ---------------- high-level loop ---------------- */
    public void scrape() {
        LocalDate date = props.getStartDate();
        while (!date.isAfter(props.getEndDate())) {
            log.info("📆  Scraping day {}", date);
            LocalDate finalDate = date;
            props.getTracks().forEach(t -> processDateTrack(finalDate, t));
            date = date.plusDays(1);
        }
    }

    /* ---------------- one (date, track) ---------------- */
    /* ---------------- one (date, track) ---------------- */
    private void processDateTrack(LocalDate date, String track) {

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

                vPage.waitForSelector("tr[data-test-id^=horse-row]");
                pPage.waitForSelector("tr[data-test-id^=horse-row]");

                /*  wait for the “Rätt kombination:” label to appear  */           //Changed!
                tPage.waitForSelector("text=\"Rätt kombination:\"");               //Changed!

                if (!isCorrectTrack(vPage, track, vUrl, date)) return;

                Map<String,String> pMap   = extractOddsMap(pPage, "[data-test-id=startlist-cell-podds]");
                Map<String,String> trioMap= extractTrioMap(tPage);                 //Changed!

                parseAndPersist(vPage.content(), date, track, lap, pMap, trioMap);
                Thread.sleep(600 + (int)(Math.random()*1200));

            } catch (PlaywrightException e) {
                log.warn("⚠️  Playwright issue on {}", vUrl, e);
                return;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /* ---------- helper: build map <startNr , Trio-odds> ---------- */
    private Map<String,String> extractTrioMap(Page page) {                         //Changed!
        Map<String,String> map = new HashMap<>();
        Document doc = Jsoup.parse(page.content());

        /* find the “Rätt kombination:” label span */
        Element labelSpan = doc.selectFirst("span:matchesOwn(^\\s*Rätt\\skombination:?)");
        if (labelSpan == null) return map;  // no trio pool on this lap

        /* the value is the next span with class ending in --value */
        Element valueSpan = labelSpan.parent().selectFirst("span[class*=\"--value\"]");
        if (valueSpan == null) return map;

        String combo = valueSpan.text().trim();   // e.g. 1-7-10

        /* find odds row (label “Odds:”) */
        Element oddsLabel = doc.selectFirst("span:matchesOwn(^\\s*Odds:?)");
        if (oddsLabel == null) return map;
        Element oddsValue = oddsLabel.parent().selectFirst("span[class*=\"--value\"]");
        if (oddsValue == null) return map;

        String odds = oddsValue.text().trim();    // e.g. 81,70

        Arrays.stream(combo.split("-")).forEach(n -> map.put(n, odds));
        return map;
    }

    /* ---------- verify nav bar shows our track ---------- */
    private boolean isCorrectTrack(Page page, String expected, String url, LocalDate date) {
        Element active = Jsoup.parse(page.content())
                .selectFirst("span[data-test-id^=calendar-menu-track-][data-test-active=true]");
        if (active == null) return false;
        String slug = slugify(
                active.attr("data-test-id").substring("calendar-menu-track-".length()));
        if (!slug.equals(expected.toLowerCase())) {
            log.info("↪️  {} not present on {} (page shows {}), skipping", expected, date, slug);
            return false;
        }
        return true;
    }

    /* ---------- helper: build map <startNr , P-odds> ---------- */
    private Map<String,String> extractOddsMap(Page page, String oddsSelector) {
        Document doc = Jsoup.parse(page.content());
        Map<String,String> map = new HashMap<>();
        for (var tr : doc.select("tr[data-test-id^=horse-row]")) {
            Element split = tr.selectFirst("[startlist-export-id^=startlist-cell-horse-split-export]");
            Element odds  = tr.selectFirst(oddsSelector);
            if (split == null || odds == null) continue;
            String[] parts = split.text().trim().split("\\s+",2);
            if (parts.length > 0) map.put(parts[0], odds.text().trim());
        }
        return map;
    }

    /* ---------- Jsoup parser (adds P & Trio) ---------- */               //Changed!
    private void parseAndPersist(String html,
                                 LocalDate date,
                                 String track,
                                 int lapNumber,
                                 Map<String,String> pMap,
                                 Map<String,String> trioMap) {              //Changed!

        Document doc = Jsoup.parse(html);
        Elements rows = doc.select("tr[data-test-id^=horse-row]");
        if (rows.isEmpty()) return;

        List<ScrapedHorse> horses = new ArrayList<>();
        int bad = 0;

        for (var tr : rows) {
            Element place = tr.selectFirst("[data-test-id=horse-placement]");
            Element split = tr.selectFirst("[startlist-export-id^=startlist-cell-horse-split-export]");
            Element vOdd = tr.selectFirst("[data-test-id=startlist-cell-vodds]");
            if (place==null||split==null||vOdd==null) { if (++bad==1) log.info("Bad row:\n{}", tr); continue; }

            String[] parts = split.text().trim().split("\\s+",2);
            String nr   = parts.length>0?parts[0]:"";
            String name = parts.length>1?parts[1]:"";

            horses.add(ScrapedHorse.builder()
                    .date(date)
                    .track(track)
                    .lap(String.valueOf(lapNumber))
                    .numberOfHorse(nr)
                    .nameOfHorse(name)
                    .placement(place.text().trim())
                    .vOdds(vOdd.text().trim())
                    .pOdds(pMap  .getOrDefault(nr,""))
                    .trioOdds(trioMap.getOrDefault(nr,""))                //Changed!
                    .build());
        }

        repo.saveAll(horses);
        log.info("💾 Saved {} horses for {} {} lap {}  ({} bad rows)", horses.size(),
                date, track, lapNumber, bad);
    }

    /* ---------- helper: slugify Swedish chars ---------- */
    private static String slugify(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase();
    }
}
