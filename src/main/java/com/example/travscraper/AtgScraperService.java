package com.example.travscraper;

import com.example.travscraper.entity.ScrapedHorse;
import com.example.travscraper.repo.ScrapedHorseRepo;
import com.microsoft.playwright.*;                      //Changed!
import jakarta.annotation.PostConstruct;               //Changed!
import jakarta.annotation.PreDestroy;                  //Changed!
import lombok.RequiredArgsConstructor;                 //Changed!
import lombok.extern.slf4j.Slf4j;                      //Changed!
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Slf4j                                                  //Changed!
@Service
@RequiredArgsConstructor                               //Changed!
public class AtgScraperService {

    /* ──────────────── DI beans ─────────────── */
    private final ScraperProperties props;             //Changed!
    private final ScrapedHorseRepo repo;               //Changed!

    /* ──────────────── Playwright ───────────── */
    private Playwright playwright;                     //Changed!
    private Browser browser;                           //Changed!

    private static final DateTimeFormatter URL_DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /* spin up headless Chromium once per JVM */      //Changed!
    @PostConstruct
    void initBrowser() {
        playwright = Playwright.create();
        browser = playwright.chromium().launch(
                new BrowserType.LaunchOptions().setHeadless(true));
        log.info("🖥️  Headless browser launched");
    }

    /* close browser on shutdown */                   //Changed!
    @PreDestroy
    void closeBrowser() {
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
    }

    /** Kick off a full scrape for the configured date range & tracks */
    public void scrape() {
        props.getTracks().forEach(track -> {
            log.info("🐎  Scraping track: {}", track);
            LocalDate date = props.getStartDate();
            while (!date.isAfter(props.getEndDate())) {
                processDateTrack(date, track);
                date = date.plusDays(1);
            }
        });
    }

    /** Load each race page in the browser, wait for table, hand to Jsoup */
    private void processDateTrack(LocalDate date, String track) {
        for (int race = 1; race <= 15; race++) {

            String url = String.format(
                    "https://www.atg.se/spel/%s/vinnare/%s/lopp/%d/resultat",
                    date.format(URL_DATE_FORMAT), track, race);       //Changed!

            try (Page page = browser.newPage()) {                    //Changed!
                page.navigate(url, new Page.NavigateOptions()
                        .setTimeout(30_000));                         // 30 s

                // Wait for at least one horse row (React finished)
                page.waitForSelector("tr[data-test-id^=horse-row]",
                        new Page.WaitForSelectorOptions()
                                .setTimeout(15_000));                //Changed!

                String html = page.content();                        //Changed!
                parseAndPersist(html, date, track, race);

                Thread.sleep(500);           // polite throttle        //Changed!

            } catch (PlaywrightException e) {
                log.warn("⚠️  Playwright issue on {}", url, e);
                // if lap 1 fails assume no more races for that day/track
                if (race == 1) break;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /* ---------- your existing Jsoup-based parser (unchanged) ---------- */
    /* ---------- final Jsoup parser, fixed selector ---------- */
    private void parseAndPersist(String html,
                                 LocalDate date,
                                 String track,
                                 int lapNumber) {

        Document doc = Jsoup.parse(html);
        Elements rows = doc.select("tr[data-test-id^=horse-row]");

        if (rows.isEmpty()) {
            log.warn("No horse rows for {} {} lap {}", date, track, lapNumber);
            return;
        }

        List<ScrapedHorse> horses = new ArrayList<>();
        int badRows = 0;

        for (var tr : rows) {

            Element placementEl = tr.selectFirst("[data-test-id=horse-placement]");
            Element splitEl     = tr.selectFirst("[startlist-export-id=startlist-cell-horse-split-export]"); //Changed!
            Element oddsEl      = tr.selectFirst("[data-test-id=startlist-cell-vodds]");

            if (placementEl == null || splitEl == null || oddsEl == null) {
                if (++badRows == 1) log.info("⚠️  Still-mismatched row:\n{}", tr.outerHtml());
                continue;
            }

            String placement = placementEl.text().trim();     // 1, 2, …
            String split     = splitEl.text().trim();         // "6 Vikens Go for it"
            String[] parts   = split.split("\\s+", 2);
            String number    = parts.length > 0 ? parts[0] : "";
            String name      = parts.length > 1 ? parts[1] : "";
            String odds      = oddsEl.text().trim();          // 23,84

            horses.add(ScrapedHorse.builder()
                    .date(date)
                    .track(track)
                    .lap(String.valueOf(lapNumber))
                    .numberOfHorse(number)
                    .nameOfHorse(name)
                    .placement(placement)
                    .odds(odds)
                    .build());
        }

        if (horses.isEmpty()) {
            log.warn("Parsed 0 horses for {} {} lap {}  ({} rows skipped)",
                    date, track, lapNumber, badRows);
            return;
        }

        repo.saveAll(horses);
        log.info("💾 Saved {} horses for {} {} lap {}  ({} rows skipped)",
                horses.size(), date, track, lapNumber, badRows);
    }

}
