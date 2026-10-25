package com.example.travscraper.service;

import com.example.travscraper.entity.DoubleGangerWarning;
import com.example.travscraper.repo.DoubleGangerWarningRepo;
import com.example.travscraper.repo.ResultHorseRepo;
import com.example.travscraper.repo.TodayHorseWarning;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class DoubleGangerWarningService {

    private static final int BATCH_SIZE = 500;
    private static final int MIN_STARTS = 8;
    private static final ZoneId STOCKHOLM = ZoneId.of("Europe/Stockholm");

    private final ResultHorseRepo resultHorseRepo;
    private final DoubleGangerWarningRepo doubleGangerWarningRepo;

    @Transactional
    public void refreshDoubleGangerWarnings() {
        int today = Integer.parseInt(LocalDate.now(STOCKHOLM).format(DateTimeFormatter.BASIC_ISO_DATE));
        log.info("Horse warnings: starting refresh for {} (minStarts={})", today, MIN_STARTS);

        List<TodayHorseWarning> flagged = resultHorseRepo.findHorsesWithWarningsOnDate(today, MIN_STARTS);
        log.info("Horse warnings: found {} result rows with doubleganger, tid 99 or too few starts", flagged.size());

        doubleGangerWarningRepo.deleteAllInBatch();

        if (flagged.isEmpty()) {
            log.info("Horse warnings: nothing to save, finished");
            return;
        }

        List<DoubleGangerWarning> buffer = new ArrayList<>(Math.min(BATCH_SIZE, flagged.size()));
        int saved = 0;

        for (TodayHorseWarning result : flagged) {
            buffer.add(DoubleGangerWarning.builder()
                    .datum(result.getDatum())
                    .bankod(result.getBankod())
                    .lopp(result.getLopp())
                    .nr(result.getNr())
                    .namn(result.getNamn())
                    .doubleGanger(result.getDoubleGanger())
                    .tid99Warning(result.getTid99Warning())
                    .horseWarning(result.getHorseWarning())
                    .starts(result.getStarts())
                    .build());

            if (buffer.size() >= BATCH_SIZE) {
                doubleGangerWarningRepo.saveAll(buffer);
                saved += buffer.size();
                log.info("Horse warnings: saved {} / {}", saved, flagged.size());
                buffer.clear();
            }
        }

        if (!buffer.isEmpty()) {
            doubleGangerWarningRepo.saveAll(buffer);
            saved += buffer.size();
        }

        log.info("Horse warnings: finished, saved {} rows to double_ganger_warning", saved);
    }
}
