package com.example.travscraper;

import com.example.travscraper.entity.FutureHorse;
import com.example.travscraper.entity.ResultHorse;
import com.example.travscraper.entity.ScrapedHorse;
import com.example.travscraper.entity.ScrapedHorseKey;
import com.example.travscraper.repo.FutureHorseRepo;
import com.example.travscraper.repo.ResultHorseRepo;
import com.example.travscraper.repo.ScrapedHorseRepo;
import com.example.travscraper.repo.StartListHorseRepo;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.LoadState;
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
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class AtgScraperService {

    private static final DateTimeFormatter URL_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter YYMMDD_FORMAT = DateTimeFormatter.ofPattern("yyMMdd");
    private static final Pattern PLACERING_WITH_R = Pattern.compile("^(\\d{1,2})r$");
    private static final Pattern ISO_DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    private static final Pattern HAS_LETTER = Pattern.compile(".*\\p{L}.*");
    private static final int RESULTAT_MAX_HORSES_PER_LAP = 999;
    private static final int RESULTAT_MAX_ROWS_PER_HORSE = 999;
    private static final Pattern TRACK_LAP_PATTERN = Pattern.compile("^\\s*(.+?)\\s*-\\s*(\\d+)\\s*$");
    private static final Pattern DIGITS_ONLY = Pattern.compile("(\\d{6,8})");
    private static final Pattern RESULT_HREF_PATTERN = Pattern.compile(
            "/spel/(\\d{4}-\\d{2}-\\d{2})/vinnare/([^/]+)/lopp/(\\d+)/resultat"
    );
    private static final Pattern PRIS_APOSTROPHE = Pattern.compile("^\\s*(\\d{1,6})\\s*['’]\\s*$");
    private static final Pattern PRIS_THOUSANDS = Pattern.compile("^\\s*(\\d{1,3}(?:[ \\u00A0\\.]\\d{3})+|\\d{4,})\\s*(?:kr)?\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern PRIS_K = Pattern.compile("^\\s*(\\d{1,6})\\s*k\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ODDS_NUMBER = Pattern.compile("^\\s*(\\d{1,4})(?:[\\.,](\\d{1,2}))?\\s*$");
    private static final String SEL_COOKIE_BUTTONS =
            "button:has-text(\"Tillåt alla\"):visible, " +
                    "button:has-text(\"Avvisa\"):visible, " +
                    "button:has-text(\"Godkänn alla cookies\"):visible, " +
                    "button:has-text(\"Jag förstår\"):visible";
    private static final String SEL_EXPAND_ALL =
            "button[data-test-id=\"expand-startlist-view\"], button:has-text(\"Utöka alla\"), button:has-text(\"Utöka\")";
    private static final String SEL_MER_INFO_VISIBLE =
            "button[data-test-id*=\"previous-starts\"][data-test-id*=\"show-more\"]:visible, " +
                    "button[data-test-id=\"previous-starts-toggle-show-more-button\"]:visible, " +
                    "button:has-text(\"Mer info\"):visible, " +
                    "button:has-text(\"Visa mer\"):visible, " +
                    "button:has-text(\"Visa mer info\"):visible";
    private static final String SEL_PREVSTARTS_WRAPPER_ANY =
            "#previous-starts-table-container, " +
                    "div[class*=\"PreviousStarts-styles--tableWrapper\"], " +
                    "div[class*=\"previousStarts-styles--tableWrapper\"], " +
                    "div[class*=\"PreviousStarts\"][class*=\"tableWrapper\"], " +
                    "div[class*=\"previousStarts\"][class*=\"tableWrapper\"]";
    private static final String SEL_PREVSTARTS_ROWS_ANY =
            "#previous-starts-table-container tbody tr, " +
                    "div[class*=\"PreviousStarts-styles--tableWrapper\"] tbody tr, " +
                    "div[class*=\"previousStarts-styles--tableWrapper\"] tbody tr, " +
                    "div[class*=\"PreviousStarts\"][class*=\"tableWrapper\"] tbody tr, " +
                    "div[class*=\"previousStarts\"][class*=\"tableWrapper\"] tbody tr, " +
                    "[data-test-id=\"result-date\"], [data-test-id=\"result-date\"] span";
    private static final Pattern TIME_VALUE = Pattern.compile("(?:\\d+\\.)?(\\d{1,2})[\\.,](\\d{1,2})");
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
            Map.entry("orkla", "Oa"),

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
    private static final String DEFAULT_BANKOD = "XX";
    private static final int RESULT_BANKOD_MAX_LEN = 20;

    static {
        Map<String, String> m = new HashMap<>();
        FULLNAME_TO_BANKODE.forEach((slug, code) -> m.put(code, slug));
        BANKODE_TO_SLUG = Collections.unmodifiableMap(m);
    }

    private final ScraperProperties props;
    private final ScrapedHorseRepo repo;
    private final FutureHorseRepo futureRepo;
    private final StartListHorseRepo startListRepo;
    private final ResultHorseRepo resultRepo;
    private final ReentrantLock lock = new ReentrantLock();
    private Playwright playwright;
    private Browser browser;
    private BrowserContext ctx;

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

    private static String extractUnderlagFromTds(Elements tds) {
        if (tds == null || tds.isEmpty()) return "";

        String best = "";
        Pattern paren = Pattern.compile("\\(([^)]{1,20})\\)");

        for (Element td : tds) {
            String raw = normalizeCellText(td.text());
            if (raw.isBlank()) continue;

            Matcher m = paren.matcher(raw);
            while (m.find()) {
                String cleaned = sanitizeUnderlag(m.group(1));
                if (cleaned.isBlank()) continue;

                if (cleaned.length() > best.length() || cleaned.length() == best.length()) {
                    best = cleaned;
                }
            }
        }

        return best;
    }

    private static String sanitizeUnderlag(String raw) {
        if (raw == null) return "";
        String t = normalizeCellText(raw).toLowerCase(Locale.ROOT);
        if (t.isBlank()) return "";

        String letters = t.replaceAll("[^a-z]", "");

        boolean hasK = letters.indexOf('k') >= 0;
        boolean hasM = letters.indexOf('m') >= 0;
        boolean hasN = letters.indexOf('n') >= 0;
        boolean hasT = letters.indexOf('t') >= 0;
        boolean hasV = letters.indexOf('v') >= 0;

        StringBuilder out = new StringBuilder(3);
        if (hasK) out.append('k');
        if (hasM) out.append('m');
        if (hasN) out.append('n');
        if (hasT) out.append('t');
        if (hasV) out.append('v');

        return out.toString();
    }

    private static String trimToMax(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max);
    }

    private static int[] findDistSparFromTds(Elements tds) {
        int[] out = new int[]{-1, -1, -1};
        if (tds == null || tds.isEmpty()) return out;

        Pattern p = Pattern.compile("^(\\d{3,4})\\s*:\\s*(\\d{1,2})?\\s*$");
        for (int i = 0; i < tds.size(); i++) {
            String txt = normalizeCellText(tds.get(i).text());
            Matcher m = p.matcher(txt);
            if (m.matches()) {
                try {
                    out[0] = Integer.parseInt(m.group(1));
                    String g2 = m.group(2);
                    out[1] = (g2 == null || g2.isBlank()) ? 1 : Integer.parseInt(g2);
                    out[2] = i;
                    return out;
                } catch (NumberFormatException ignored) {
                }
            }
        }

        Pattern distOnly = Pattern.compile("^(\\d{3,4})\\s*(?:m)?\\s*$", Pattern.CASE_INSENSITIVE);
        for (int i = 0; i < tds.size(); i++) {
            String txt = normalizeCellText(tds.get(i).text());
            Matcher m = distOnly.matcher(txt);
            if (!m.matches()) continue;

            int dist;
            try {
                dist = Integer.parseInt(m.group(1));
            } catch (NumberFormatException e) {
                continue;
            }

            if (dist < 600 || dist > 4000) continue;

            String next = (i + 1 < tds.size()) ? normalizeCellText(tds.get(i + 1).text()) : "";
            boolean nextLooksLikeTime = false;
            if (!next.isBlank()) {
                TidInfo ti = parseTidCell(next, true);
                nextLooksLikeTime = (ti.tid() != null || ti.startmetod() != null || ti.galopp() != null
                        || TIME_VALUE.matcher(next).find());
            }
            if (!nextLooksLikeTime) continue;

            out[0] = dist;
            out[1] = 1;
            out[2] = i;
            return out;
        }

        return out;
    }

    private static Integer mapPlaceringValue(String raw) {
        String t = normalizeCellText(raw).toLowerCase(Locale.ROOT);
        if (t.isBlank()) return null;

        String token = t.split("\\s+")[0].replaceAll("[^0-9\\p{L}]", "");
        if (token.isBlank()) return null;

        Matcher mr = PLACERING_WITH_R.matcher(token);
        if (mr.matches()) token = mr.group(1);

        if (token.equals("k") || token.equals("p") || token.equals("str") || token.equals("d")) return 99;

        if (!token.matches("^\\d+$")) return null;
        if (token.length() > 2) return null;

        try {
            int v = Integer.parseInt(token);
            if (v == 0 || v == 9) return 15;
            return v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer extractPlaceringFromTds(Elements tds, int distIdx) {
        if (tds == null || tds.isEmpty()) return null;

        for (int i = 0; i < tds.size(); i++) {
            Element td = tds.get(i);
            if (td.selectFirst("span[style*=font-weight]") != null) {
                Integer v = mapPlaceringValue(td.text());
                if (v != null) return v;
            }
        }

        int max = (distIdx >= 0 ? distIdx : tds.size());
        for (int i = 0; i < max; i++) {
            Integer v = mapPlaceringValue(tds.get(i).text());
            if (v != null) return v;
        }

        return null;
    }

    private static Integer parsePlaceringRawDigit1to9(String raw) {
        String t = normalizeCellText(raw).toLowerCase(Locale.ROOT);
        if (t.isBlank()) return null;

        String token = t.split("\\s+")[0].replaceAll("[^0-9\\p{L}]", "");
        if (token.isBlank()) return null;

        Matcher mr = PLACERING_WITH_R.matcher(token);
        if (mr.matches()) token = mr.group(1);

        if (token.matches("^[1-9]$")) {
            return Integer.parseInt(token);
        }

        return null;
    }

    private static Integer extractPlaceringRawDigit1to9FromTds(Elements tds, int distIdx) {
        if (tds == null || tds.isEmpty()) return null;

        for (int i = 0; i < tds.size(); i++) {
            Element td = tds.get(i);
            if (td.selectFirst("span[style*=font-weight]") != null) {
                Integer v = parsePlaceringRawDigit1to9(td.text());
                if (v != null) return v;
            }
        }

        int max = (distIdx >= 0 ? distIdx : tds.size());
        for (int i = 0; i < max; i++) {
            Integer v = parsePlaceringRawDigit1to9(tds.get(i).text());
            if (v != null) return v;
        }

        return null;
    }


    private static TidInfo extractTidFromTds(Elements tds, int distIdx) {
        if (tds == null || tds.isEmpty()) return new TidInfo(null, null, null);

        if (distIdx >= 0 && distIdx + 1 < tds.size()) {
            TidInfo info = parseTidCell(tds.get(distIdx + 1).text(), true);
            if (info.tid != null || info.startmetod != null || info.galopp != null) return info;
        }

        for (int i = 0; i < tds.size(); i++) {
            String raw = normalizeCellText(tds.get(i).text());
            if (raw.contains(":")) continue;

            TidInfo info = parseTidCell(raw);
            if (info.tid == null && info.startmetod == null && info.galopp == null) continue;

            if (info.tid != null && Double.compare(info.tid, 99.0) == 0) return info;

            if (info.tid != null && info.startmetod == null && info.galopp == null && raw.matches(".*\\d+[\\.,]\\d{2}.*")) {
                continue;
            }

            if (info.tid != null && info.tid >= 8.0 && info.tid <= 60.0) return info;

            if (info.startmetod != null || info.galopp != null) return info;
        }

        return new TidInfo(null, null, null);
    }

    private static Integer parseFirstInt(String s) {
        String txt = normalizeCellText(s);
        Matcher m = Pattern.compile("(\\d{1,4})").matcher(txt);
        if (!m.find()) return null;
        try {
            return Integer.parseInt(m.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static TidInfo parseTidCell(String raw) {
        return parseTidCell(raw, false);
    }

    private static TidInfo parseTidCell(String raw, boolean integerNoCommaMeans99) {
        String t = normalizeCellText(raw).toLowerCase(Locale.ROOT);
        if (t.isBlank()) return new TidInfo(null, null, null);

        t = t.replaceAll("[()\\s]", "");

        String letters = t.replaceAll("[0-9\\.,]", "");

        String startmetod = letters.contains("a") ? "a" : null;
        String galopp = letters.contains("g") ? "g" : null;

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

        if (time == null) {
            boolean hasSep = t.contains(",") || t.contains(".");
            String digits = t.replaceAll("\\D+", "");
            if (!hasSep && !digits.isBlank() && digits.length() <= 2) {
                if (integerNoCommaMeans99 || !letters.isBlank()) {
                    return new TidInfo(99.0, startmetod, galopp);
                }
            }
        }

        return new TidInfo(time, startmetod, galopp);
    }

    private static String normalizeCellText(String s) {
        if (s == null) return "";
        return s.replace('\u00A0', ' ').trim();
    }

    private static String normalizeHorseNameSimple(String raw) {
        String s = normalizeCellText(raw);
        if (s.isBlank()) return "";
        s = s.replace("'", "").replace("’", "");
        s = s.toUpperCase(Locale.ROOT);
        return trimToMax(s, 50);
    }

    private static String toResultBankod(String trackLike) {
        if (trackLike == null || trackLike.isBlank()) return DEFAULT_BANKOD;

        String key = trackKey(trackLike);
        String code = FULLNAME_TO_BANKODE.get(key);
        if (code != null && !code.isBlank()) return code;

        String fallback = trimToMax(key, RESULT_BANKOD_MAX_LEN);
        if (fallback.isBlank()) return DEFAULT_BANKOD;

        log.warn("⚠️  Okänd bana '{}' (key='{}'), använder '{}' som bankod i RESULT-tabellen",
                trackLike, key, fallback);
        return fallback;
    }

    private static Integer extractPrisFromTds(Elements tds, int distIdx) {
        if (tds == null || tds.isEmpty()) return 0;

        int start = (distIdx >= 0 ? Math.min(distIdx + 2, tds.size()) : 0);
        Integer last = null;

        for (int i = start; i < tds.size(); i++) {
            String raw = normalizeCellText(tds.get(i).text());
            Integer v = parsePrisToInt(raw);
            if (v != null) last = v;
        }

        return last != null ? last : 0;
    }

    private static Integer parsePrisToInt(String raw) {
        String t = normalizeCellText(raw);
        if (t.isBlank()) return null;

        t = t.replaceAll("[()]", "").trim();

        Matcher ma = PRIS_APOSTROPHE.matcher(t);
        if (ma.matches()) {
            try {
                return Integer.parseInt(ma.group(1)) * 1000;
            } catch (NumberFormatException e) {
                return null;
            }
        }

        Matcher mk = PRIS_K.matcher(t);
        if (mk.matches()) {
            try {
                return Integer.parseInt(mk.group(1)) * 1000;
            } catch (NumberFormatException e) {
                return null;
            }
        }

        if (t.contains(",") && !t.contains(" ")) return null;

        Matcher mt = PRIS_THOUSANDS.matcher(t);
        if (mt.matches()) {
            String digits = mt.group(1).replaceAll("[\\s\\u00A0\\.]", "");
            try {
                return Integer.parseInt(digits);
            } catch (NumberFormatException e) {
                return null;
            }
        }

        return null;
    }

    private static Integer extractOddsFromTds(Elements tds, int distIdx) {
        if (tds == null || tds.isEmpty()) return 999;

        int timeIdx = (distIdx >= 0 ? distIdx + 1 : -1);
        int start = (timeIdx >= 0 ? Math.min(timeIdx + 1, tds.size()) : 0);

        Integer last = null;
        for (int i = start; i < tds.size(); i++) {
            String raw = normalizeCellText(tds.get(i).text());
            if (raw.isBlank()) continue;

            if (raw.contains("'") || raw.contains("’")) continue;

            Integer v = parseOddsToInt(raw);
            if (v != null) last = v;
        }

        return last != null ? last : 999;
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

    private static int findHeaderIndex(Element container, Pattern headerRx) {
        if (container == null) return -1;
        Element head = container.selectFirst("thead");
        if (head == null) return -1;

        Elements hs = head.select("th, td");
        for (int i = 0; i < hs.size(); i++) {
            String txt = normalizeCellText(hs.get(i).text());
            if (headerRx.matcher(txt).find()) return i;
        }
        return -1;
    }

    private static String textAt(Elements tds, int idx) {
        if (tds == null || idx < 0 || idx >= tds.size()) return "";
        return normalizeCellText(tds.get(idx).text());
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

    private List<String> tracksForForeign(LocalDate date) {
        List<String> codes = new ArrayList<>();
        codes.add("Bd");
        codes.add("Br");
        codes.add("Bt");
        codes.add("Dr");
        codes.add("Fs");
        codes.add("Ha");
        codes.add("Ht");
        codes.add("Ja");
        codes.add("Kl");
        codes.add("Le");
        codes.add("Mo");
        codes.add("Sö");

        codes.add("Aa");
        codes.add("Bi");
        codes.add("Bm");
        codes.add("Ch");
        codes.add("Ny");
        codes.add("Od");
        codes.add("Se");
        codes.add("Ål");

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
                        .setArgs(List.of("--disable-blink-features=AutomationControlled"))
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
                        "alborg"
                );

                for (String track : tracks) {
                    processDateTrackFuture(date, track);
                }

                for (String track : hardcodedTracks) {
                    processDateTrackFuture(date, track);
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
                     försök minska anti-bot-detektering
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

                if (!isCorrectTrack(page, track, date)) return;
                if (!isCorrectLap(page, lap, track, date)) {
                    log.info("🔸 Lap {} missing on {} {} (future), continuing", lap, date, track);
                    if (++consecutiveMisses >= 2) break;
                    continue;
                }

                consecutiveMisses = 0;

                parseAndPersistFuture(page.content(), date, track, lap);

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

            String normalizedName = normalizeHorseNameSimple(name);

            String bankode = toKnownBankodOrNull(track);
            if (bankode == null) {
                log.warn("⚠️  Okänd bana '{}' -> skippar RESULTS (ScrapedHorse) helt", track);
                continue;
            }

            ScrapedHorseKey key = new ScrapedHorseKey(date, bankode, String.valueOf(lap), nr);

            Optional<ScrapedHorse> existingHorseOpt = repo.findById(key);

            ScrapedHorse horse;
            if (existingHorseOpt.isPresent()) {
                horse = existingHorseOpt.get();

                horse.setNameOfHorse(normalizedName);
                horse.setPlacement(place.text().trim());
                horse.setVOdds(vOdd.text().trim());
                horse.setPOdds(pMap.getOrDefault(nr, ""));
                horse.setTrioOdds(trioMap.getOrDefault(nr, ""));
            } else {
                horse = ScrapedHorse.builder()
                        .date(date).track(bankode).lap(String.valueOf(lap))
                        .numberOfHorse(nr).nameOfHorse(normalizedName).placement(place.text().trim())
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
                "alborg"
        );

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

            String normalizedName = normalizeHorseNameSimple(name);

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
                        // Om vi försökte INSERT:a en ny rad men den hann skapas av någon annan,
                        // gör en lookup och uppdatera odds om den fortfarande är 999/null.
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

                            } else {
                                log.warn("⚠️  (future) Kunde inte hitta rad efter krock datum={} bankod={} lopp={} namn={}",
                                        rh.getDatum(), rh.getBankod(), rh.getLopp(), rh.getNamn());
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

    public void runResultatPopupScrape() {
        scrapeResultatPopupsOnly();
    }

    public void scrapeResultatPopupsOnly() {
        if (!lock.tryLock()) {
            log.warn("⏳ Previous scrape still running – skipping (resultat popups)");
            return;
        }
        try {
            LocalDate end = Optional.ofNullable(props.getEndDateResultatPopup())
                    .orElse(LocalDate.now(ZoneId.of("Europe/Stockholm")).minusDays(1));
            LocalDate start = Optional.ofNullable(props.getStartDateResultatPopup())
                    .orElse(end);

            for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
                log.info("📆  Scraping ATG HISTORY (popup) {}", date);
                List<String> tracks = tracksFor(date);
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
                        "alborg"
                );
                    for (String track : tracks) {
                        processDateTrackResultatPopups(date, track);
                    }

                for (String track : hardcodedTracks) {
                    processDateTrackResultatPopups(date, track);
                }
            }
        } finally {
            lock.unlock();
        }
    }

    private void processDateTrackResultatPopups(LocalDate date, String track) {
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
                    if (++consecutiveMisses >= 2) break;
                    continue;
                }

                String finalUrl = page.url();
                if (finalUrl.contains("/lopp/") && !finalUrl.contains("/lopp/" + lap)) {
                    log.info("↪️  (resultat) lopp {} redirectade till {}, hoppar", lap, finalUrl);
                    if (++consecutiveMisses >= 2) break;
                    continue;
                }

                page.waitForSelector(
                        "button:has-text(\"Tillåt alla\"):visible, " +
                                "button:has-text(\"Avvisa\"):visible, " +
                                "tr[data-test-id^=horse-row]",
                        new Page.WaitForSelectorOptions().setTimeout(60_000));

                dismissCookiesIfPresent(page);

                page.waitForSelector("tr[data-test-id^=horse-row]",
                        new Page.WaitForSelectorOptions().setTimeout(60_000));

                if (isCancelledRace(page)) {
                    if (++consecutiveMisses >= 2) break;
                    continue;
                }

                if (!isCorrectTrack(page, track, date)) return;

                consecutiveMisses = 0;

                scrapeResultatFromPopups(page, date, track, lap);

            } catch (PlaywrightException e) {
                log.warn("⚠️  (resultat) Playwright-fel på {}: {}", url, e.getMessage());
                if (e.getMessage() != null && e.getMessage().contains("Timeout")) {
                    if (++consecutiveMisses >= 2) break;
                    continue;
                }
                break;
            }
        }
    }

    private void dismissCookiesIfPresent(Page page) {
        try {
            Locator cookie = page.locator(SEL_COOKIE_BUTTONS);
            if (cookie.count() > 0) {
                robustClick(cookie.first(), 10_000);
                try {
                    page.waitForTimeout(250);
                } catch (PlaywrightException ignored) {
                }
            }
        } catch (PlaywrightException ignored) {
        }
    }

    private void robustClick(Locator btn, int timeoutMs) {
        try {
            btn.scrollIntoViewIfNeeded();
        } catch (PlaywrightException ignored) {
        }

        try {
            btn.click(new Locator.ClickOptions().setTimeout(timeoutMs).setForce(true));
        } catch (PlaywrightException e) {
            try {
                btn.evaluate("el => el.click()");
            } catch (PlaywrightException ignored) {
            }
        }
    }

    private void clickExpandAllIfPresent(Page page) {
        dismissCookiesIfPresent(page);

        Locator expand = page.locator(SEL_EXPAND_ALL);

        if (expand.count() == 0) {
            try {
                expand = page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName(
                        Pattern.compile("Utöka\\s+alla|Utöka", Pattern.CASE_INSENSITIVE)));
            } catch (PlaywrightException ignored) {
            }
        }

        if (expand.count() <= 0) {
            log.info("🟦 (atg history) Hittade ingen 'Utöka/Utöka alla' på {}", page.url());
            return;
        }

        Locator btn = expand.first();
        try {
            robustClick(btn, 15_000);
            try {
                page.waitForLoadState(LoadState.NETWORKIDLE);
            } catch (PlaywrightException ignored) {
            }
        } catch (PlaywrightException e) {
            log.warn("⚠️  (resultat) Kunde inte klicka 'Utöka/Utöka alla' på {}: {}", page.url(), e.getMessage());
        }

        try {
            page.waitForSelector(SEL_MER_INFO_VISIBLE, new Page.WaitForSelectorOptions()
                    .setTimeout(6_000)
                    .setState(WaitForSelectorState.ATTACHED));
        } catch (PlaywrightException ignored) {
        }
    }

    private void tryExpandSomeRowsIfMerInfoHidden(Page page) {
        int rawText = page.locator("text=Mer info").count();
        int visibleButtons = page.locator(SEL_MER_INFO_VISIBLE).count();
        if (rawText == 0 || visibleButtons > 0) return;

        Locator rows = page.locator("tr[data-test-id^=horse-row]");
        int n = Math.min(rows.count(), RESULTAT_MAX_HORSES_PER_LAP);

        for (int i = 0; i < n; i++) {
            Locator row = rows.nth(i);
            try {
                // Försök klicka en expander-knapp om den finns, annars klicka hela raden
                Locator expander = row.locator("button[aria-expanded], [aria-expanded]");
                if (expander.count() > 0) {
                    String ae = expander.first().getAttribute("aria-expanded");
                    if (!"true".equalsIgnoreCase(ae)) {
                        robustClick(expander.first(), 3_000);
                    }
                } else {
                    robustClick(row, 3_000);
                }
            } catch (PlaywrightException ignored) {
            }
        }

        try {
            page.waitForTimeout(250);
        } catch (PlaywrightException ignored) {
        }
    }

    private Locator findMerInfoButtons(Page page) {
        Locator byDataTestId = page.locator(
                "button[data-test-id=\"previous-starts-toggle-show-more-button\"]:visible, " +
                        "button[data-test-id*=\"previous-starts\"][data-test-id*=\"show-more\"]:visible"
        );
        if (byDataTestId.count() > 0) return byDataTestId;

        Locator byText = page.locator(
                "button:has-text(\"Mer info\"):visible, " +
                        "button:has-text(\"Visa mer\"):visible, " +
                        "button:has-text(\"Visa mer info\"):visible, " +
                        "a:has-text(\"Mer info\"):visible, " +
                        "[role=button]:has-text(\"Mer info\"):visible"
        );
        if (byText.count() > 0) return byText;

        try {
            Locator byRole = page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions()
                    .setName(Pattern.compile("Mer\\s+info|Visa\\s+mer(\\s+info)?", Pattern.CASE_INSENSITIVE)));
            if (byRole.count() > 0) return byRole;
        } catch (PlaywrightException ignored) {
        }
        return page.locator("button:visible").filter(new Locator.FilterOptions().setHasText(
                Pattern.compile("Mer\\s+info|Visa\\s+mer(\\s+info)?", Pattern.CASE_INSENSITIVE)));
    }

    private void scrapeResultatFromPopups(Page page, LocalDate meetingDate, String meetingTrackSlug, int meetingLap) {
        clickExpandAllIfPresent(page);
        tryExpandSomeRowsIfMerInfoHidden(page);


        int textCount = page.locator("text=Mer info").count();
        int dataTestIdCount = page.locator("[data-test-id*=\"previous-starts\"][data-test-id*=\"show-more\"]").count();

        Locator merInfoButtons = findMerInfoButtons(page);
        int total = merInfoButtons.count();

        if (total <= 0) {
            log.info("🟦 (resultat) Inga 'Mer info/Visa mer' hittades på {} {} lopp {} (selCount={}, textCount={}, dataTestIdCount={})",
                    meetingDate, meetingTrackSlug, meetingLap, total, textCount, dataTestIdCount);
            return;
        }

        int toClick = Math.min(total, RESULTAT_MAX_HORSES_PER_LAP);
        log.info("🟩 (atg history) Hittade {} 'Mer info/Visa mer' på {} {} lopp {} (klickar {})",
                total, meetingDate, meetingTrackSlug, meetingLap, toClick);

        for (int i = 0; i < toClick; i++) {
            Locator btn = merInfoButtons.nth(i);
            String horseName = "";
            Integer horseNr = null;
            Locator scopeRow = null;

            try {
                Locator tr = btn.locator("xpath=ancestor::tr[1]");
                scopeRow = tr;

                Locator split = tr.locator("[startlist-export-id^='startlist-cell-horse-split-export']");
                if (split.count() == 0) {
                    Locator prev = tr.locator("xpath=preceding-sibling::tr[1]");
                    if (prev.count() > 0) {
                        split = prev.locator("[startlist-export-id^='startlist-cell-horse-split-export']");
                    }
                }

                if (split.count() > 0) {
                    String splitTxt = split.first().innerText().trim();
                    String[] parts = splitTxt.split("\\s+", 2);


                    if (parts.length > 0) {
                        String nrStr = parts[0].replaceAll("\\D+", "");
                        if (!nrStr.isBlank()) {
                            try {
                                horseNr = Integer.parseInt(nrStr);
                            } catch (NumberFormatException ignored) {
                            }
                        }
                    }
                    horseName = (parts.length > 1) ? parts[1].trim() : splitTxt;
                    horseName = normalizeHorseNameSimple(horseName);
                }

                robustClick(btn, 15_000);

                Locator waitScope = btn.locator("xpath=ancestor::tr[1]");
                Locator rowsInScope = waitScope.locator("tbody tr");
                if (rowsInScope.count() == 0) {
                    rowsInScope = waitScope.locator("[data-test-id=\"result-date\"]");
                }
                if (rowsInScope.count() > 0) {
                    rowsInScope.first().waitFor(new Locator.WaitForOptions().setTimeout(30_000));
                } else {
                    page.waitForSelector(SEL_PREVSTARTS_ROWS_ANY,
                            new Page.WaitForSelectorOptions().setTimeout(30_000).setState(WaitForSelectorState.ATTACHED));
                }

                String fragment = waitScope.evaluate("el => el.outerHTML").toString();

                parseAndPersistResultatFromPreviousStarts(fragment, meetingDate, meetingTrackSlug, meetingLap, horseName, horseNr, i);

                closePreviousStarts(page, btn, waitScope);

            } catch (PlaywrightException e) {
                log.warn("⚠️  (resultat) Kunde inte öppna/scrapa 'Mer info/Visa mer' på {} {} lopp {}: {}",
                        meetingDate, meetingTrackSlug, meetingLap, e.getMessage());
                try {
                    closePreviousStarts(page, btn, scopeRow);
                } catch (PlaywrightException ignored) {
                }
            }
        }

    }

    private void closePreviousStarts(Page page, Locator merInfoBtn, Locator scopeRow) {
        try {
            page.keyboard().press("Escape");
        } catch (PlaywrightException ignored) {
        }

        try {
            Locator close = page.locator(
                    "button[aria-label='Stäng'], button[aria-label='Close'], button:has-text(\"Stäng\"), button:has-text(\"Close\")"
            );
            if (close.count() > 0) {
                robustClick(close.first(), 5_000);
            }
        } catch (PlaywrightException ignored) {
        }

        try {
            if (scopeRow != null) {
                if (scopeRow.locator(SEL_PREVSTARTS_WRAPPER_ANY).count() > 0) {
                    robustClick(merInfoBtn, 5_000);
                }
            } else {

                if (page.locator(SEL_PREVSTARTS_WRAPPER_ANY).count() > 0) {
                    robustClick(merInfoBtn, 5_000);
                }
            }
        } catch (PlaywrightException ignored) {
        }
    }

    private void parseAndPersistResultatFromPreviousStarts(
            String html,
            LocalDate meetingDate,
            String meetingTrackSlug,
            int meetingLap,
            String horseName,
            Integer horseNr,
            int horseIdx
    ) {
        Document doc = Jsoup.parse(html);

        Element container = doc.selectFirst("#previous-starts-table-container");
        if (container == null) {
            container = doc.selectFirst(
                    "div[class*=\"PreviousStarts-styles--tableWrapper\"], " +
                            "div[class*=\"previousStarts\"]"
            );
        }

        if (container == null) {
            log.warn("⚠️  (resultat) Hittade inte previous-starts-container för häst='{}' på {} {} lopp {}",
                    horseName, meetingDate, meetingTrackSlug, meetingLap);
            return;
        }

        Element table = container.selectFirst("table");
        if (table == null) {
            log.warn("⚠️  (resultat) Hittade ingen table i previous-starts för häst='{}' på {} {} lopp {}",
                    horseName, meetingDate, meetingTrackSlug, meetingLap);
            return;
        }

        Elements rows = table.select("tbody tr");
        if (rows.isEmpty()) rows = table.select("tr");

        if (rows.isEmpty()) {
            log.warn("⚠️  (resultat) Inga rader i previous-starts på {} {} lopp {}",
                    meetingDate, meetingTrackSlug, meetingLap);
            return;
        }

        String safeName = normalizeHorseNameSimple(horseName);

        int kuskIdx = findHeaderIndex(container, Pattern.compile("\\bkusk\\b", Pattern.CASE_INSENSITIVE));

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

            Integer prisRaw = extractPrisFromTds(tds, distIdx);
            Integer rawPlaceringForPrize = extractPlaceringRawDigit1to9FromTds(tds, distIdx);
            Integer pris = 0;
            if (rawPlaceringForPrize != null && prisRaw != null && prisRaw > 0) {
                pris = prisRaw / rawPlaceringForPrize;
            }

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
                            .nr(0)
                            .namn(safeName)
                            .build());

            if (!safeName.isBlank()) {
                rh.setNamn(safeName);
            }

            if (rh.getNr() == null) {
                rh.setNr(0);
            }

            if (distans != null) rh.setDistans(distans);
            if (spar != null) rh.setSpar(spar);
            if (placering != null) rh.setPlacering(placering);
            if (tid != null) rh.setTid(tid);
            if (startmetod != null && !startmetod.isBlank()) rh.setStartmetod(startmetod);
            if (galopp != null && !galopp.isBlank()) rh.setGalopp(galopp);

            if (rh.getId() == null || rh.getPris() == null) {
                rh.setPris(pris);
            }
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
                    toSave.size(), meetingDate, meetingTrackSlug, meetingLap);
        } catch (DataIntegrityViolationException dive) {
            log.warn("⚠️  (resultat) saveAll krockade, kör fallback rad-för-rad (nr=keep/default0) på {} {} lopp {}: {}",
                    meetingDate, meetingTrackSlug, meetingLap, dive.getMostSpecificCause().getMessage());

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
                    if (rh.getStartmetod() != null && !rh.getStartmetod().isBlank())
                        existing.setStartmetod(rh.getStartmetod());
                    if (rh.getGalopp() != null && !rh.getGalopp().isBlank()) existing.setGalopp(rh.getGalopp());

                    if (existing.getId() == null || existing.getPris() == null) {
                        existing.setPris(rh.getPris());
                    }
                    existing.setOdds(rh.getOdds());

                    if (rh.getUnderlag() != null && !rh.getUnderlag().isBlank()) existing.setUnderlag(rh.getUnderlag());
                    else if (existing.getUnderlag() == null) existing.setUnderlag("");

                    if (rh.getKusk() != null && !rh.getKusk().isBlank()) existing.setKusk(rh.getKusk());

                    if (existing.getNr() == null) {
                        existing.setNr(0);
                    }

                    resultRepo.save(existing);
                } catch (DataIntegrityViolationException ignored) {
                    log.warn("⚠️  (resultat) Kunde inte upserta datum={} bankod={} lopp={} nr=keep/default0",
                            rh.getDatum(), rh.getBankod(), rh.getLopp());
                }
            }
        }
    }



    private long stableResultId(LocalDate meetingDate, String meetingTrackSlug, int meetingLap, String horseName, int horseIdx, int rowIdx,
                                Integer datum, String bankod, Integer lopp) {
        String key = meetingDate + "|" + meetingTrackSlug + "|" + meetingLap + "|" +
                (horseName == null ? "" : horseName) + "|" + horseIdx + "|" + rowIdx + "|" +
                datum + "|" + bankod + "|" + lopp;
        UUID uuid = UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
        return uuid.getMostSignificantBits() & Long.MAX_VALUE;
    }

    private Integer extractDatumFromResultRow(Element tr) {
        Element dateEl = tr.selectFirst("[data-test-id=result-date]");
        if (dateEl != null) {
            String href = dateEl.hasAttr("href") ? dateEl.attr("href") : "";
            if (href != null && !href.isBlank()) {
                Matcher mh = RESULT_HREF_PATTERN.matcher(href);
                if (mh.find()) {
                    try {
                        LocalDate d = LocalDate.parse(mh.group(1), URL_DATE_FORMAT);
                        return Integer.parseInt(d.format(DateTimeFormatter.BASIC_ISO_DATE));
                    } catch (Exception ignored) {
                    }
                }
            }

            String txt = normalizeCellText(dateEl.text());
            if (!txt.isBlank()) {
                if (ISO_DATE.matcher(txt).matches()) {
                    try {
                        LocalDate d = LocalDate.parse(txt, URL_DATE_FORMAT);
                        return Integer.parseInt(d.format(DateTimeFormatter.BASIC_ISO_DATE));
                    } catch (Exception ignored) {
                    }
                }

                Matcher m = DIGITS_ONLY.matcher(txt);
                if (m.find()) {
                    String digits = m.group(1).replaceAll("\\D+", "");
                    try {
                        if (digits.length() == 6) return 20000000 + Integer.parseInt(digits);
                        if (digits.length() == 8) return Integer.parseInt(digits);
                    } catch (Exception ignored) {
                    }
                }
            }
        }

        return null;
    }

    private TrackLap parseTrackLapText(String raw) {
        String t = normalizeCellText(raw);
        if (t.isBlank()) return null;

        if (ISO_DATE.matcher(t).matches()) return null;
        if (t.matches("^\\d{6}$") || t.matches("^\\d{8}$")) return null;

        Matcher m = TRACK_LAP_PATTERN.matcher(t);
        if (!m.matches()) return null;

        String trackName = m.group(1).trim();
        if (!HAS_LETTER.matcher(trackName).matches()) return null;

        int lopp;
        try {
            lopp = Integer.parseInt(m.group(2));
        } catch (NumberFormatException e) {
            return null;
        }

        return new TrackLap(toResultBankod(trackName), lopp);
    }

    private TrackLap extractTrackLapFromResultRow(Element tr) {

        Element dateEl = tr.selectFirst("[data-test-id=result-date]");
        if (dateEl != null) {
            String href = dateEl.hasAttr("href") ? dateEl.attr("href") : "";
            if (href != null && !href.isBlank()) {
                Matcher mh = RESULT_HREF_PATTERN.matcher(href);
                if (mh.find()) {
                    String trackSlug = mh.group(2).trim();
                    try {
                        int lopp = Integer.parseInt(mh.group(3));
                        String bankod = toResultBankod(trackSlug);
                        return new TrackLap(bankod, lopp);
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }

        for (Element td : tr.select("td")) {
            TrackLap tl = parseTrackLapText(td.text());
            if (tl != null) return tl;
        }

        return null;
    }

    private ResultHorse buildOrUpdateResultOddsForFuture(LocalDate date, String bankod, int lap, String horseName,
                                                         String startNumber, String vOdds, boolean allowCreateIfMissing) {

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

    private record TrackLap(String bankod, Integer lopp) {
    }

    private record TidInfo(Double tid, String startmetod, String galopp) {
    }
}
