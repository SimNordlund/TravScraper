package com.example.travscraper.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class RioCalculationService {

    private static final DateTimeFormatter RANK_DATE_FORMAT = DateTimeFormatter.BASIC_ISO_DATE;
    private static final Pattern LEADING_DECIMAL = Pattern.compile("^[+-]?(?:\\d+(?:[.,]\\d*)?|[.,]\\d+)");
    private static final Pattern LEADING_INTEGER = Pattern.compile("^\\d+");

    private static final String FIND_TIPPED_HORSES = """
            select startdatum, bankod, cast(lopp as int) as lopp, nr, id as rankid
            from rank
            where tips > 0
              and spelform = 'Vinnare'
              and starter = '0'
            order by startdatum asc, bankod desc, lopp asc, procentanalys desc, nr asc
            """;

    private static final String FIND_SCRAPED_RESULT = """
            select v_odds, p_odds, placement
            from scraped_horse
            where date = ?
              and track = ?
              and lap = ?
              and number_of_horse = ?
            limit 1
            """;

    private static final String INSERT_RIO = """
            insert into roi (rankid, roitotalt, roivinnare, roiplats, resultat, roihast, insats)
            values (?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbcTemplate;

    @Transactional
    public void CalculateRio() {
        log.info("RIO: starting calculation");

        jdbcTemplate.update("delete from roi");

        List<RankedHorse> rankedHorses = jdbcTemplate.query(FIND_TIPPED_HORSES, (rs, rowNum) ->
                new RankedHorse(
                        parseRankDate(rs.getObject("startdatum")),
                        rs.getString("bankod"),
                        rs.getInt("lopp"),
                        rs.getString("nr"),
                        rs.getLong("rankid")
                ));

        BigDecimal totalRio = BigDecimal.ZERO;
        int saved = 0;

        for (RankedHorse rankedHorse : rankedHorses) {
            ScrapedResult result = findScrapedResult(rankedHorse);
            RioValues rio = calculateRio(result);
            totalRio = totalRio.add(rio.horseRio());

            jdbcTemplate.update(
                    INSERT_RIO,
                    rankedHorse.rankId(),
                    totalRio,
                    rio.winningOdds(),
                    rio.placeOdds(),
                    rio.placement(),
                    rio.horseRio(),
                    BigDecimal.valueOf(200)
            );
            saved++;
        }

        log.info("RIO: finished, saved {} rows to roi", saved);
    }

    private ScrapedResult findScrapedResult(RankedHorse rankedHorse) {
        List<ScrapedResult> results = jdbcTemplate.query(
                FIND_SCRAPED_RESULT,
                (rs, rowNum) -> new ScrapedResult(
                        rs.getString("v_odds"),
                        rs.getString("p_odds"),
                        rs.getString("placement")
                ),
                rankedHorse.date(),
                rankedHorse.track(),
                String.valueOf(rankedHorse.lap()),
                rankedHorse.horseNumber()
        );

        return results.isEmpty() ? null : results.get(0);
    }

    private RioValues calculateRio(ScrapedResult result) {
        if (result == null) {
            return new RioValues(BigDecimal.ZERO, BigDecimal.ZERO, 99, BigDecimal.ZERO);
        }

        BigDecimal winningOdds = parsePositiveDecimal(result.winningOdds());
        BigDecimal placeOdds = parsePositiveDecimal(result.placeOdds());
        int placement = parsePositiveInteger(result.placement());

        boolean won = placement == 1;
        boolean placed = placement >= 1 && placement <= 3;

        BigDecimal winningRio = won ? winningOdds.subtract(BigDecimal.ONE) : BigDecimal.ONE.negate();
        BigDecimal placeRio = placed ? placeOdds.subtract(BigDecimal.ONE) : BigDecimal.ONE.negate();
        BigDecimal horseRio = winningRio.add(placeRio).multiply(BigDecimal.valueOf(100));

        return new RioValues(
                won ? winningOdds : BigDecimal.ZERO,
                placed ? placeOdds : BigDecimal.ZERO,
                placement,
                horseRio
        );
    }

    private LocalDate parseRankDate(Object value) {
        if (value == null) {
            throw new IllegalStateException("RIO: rank.startdatum is null");
        }

        return LocalDate.parse(value.toString().trim(), RANK_DATE_FORMAT);
    }

    private BigDecimal parsePositiveDecimal(String value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }

        Matcher matcher = LEADING_DECIMAL.matcher(value.trim());
        if (!matcher.find()) {
            return BigDecimal.ZERO;
        }

        BigDecimal parsed = new BigDecimal(matcher.group().replace(',', '.'));
        return parsed.signum() > 0 ? parsed : BigDecimal.ZERO;
    }

    private int parsePositiveInteger(String value) {
        if (value == null) {
            return 0;
        }

        Matcher matcher = LEADING_INTEGER.matcher(value.trim());
        if (!matcher.find()) {
            return 0;
        }

        int parsed = Integer.parseInt(matcher.group());
        return Math.max(parsed, 0);
    }

    private record RankedHorse(LocalDate date, String track, int lap, String horseNumber, long rankId) {
    }

    private record ScrapedResult(String winningOdds, String placeOdds, String placement) {
    }

    private record RioValues(BigDecimal winningOdds, BigDecimal placeOdds, int placement, BigDecimal horseRio) {
    }
}
