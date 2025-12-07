// src/main/java/com/example/travscraper/Service/HorseWarningService.java
package com.example.travscraper.Service;

import com.example.travscraper.entity.HorseWarning;
import com.example.travscraper.repo.HorseStartSummary;
import com.example.travscraper.repo.HorseWarningRepo;
import com.example.travscraper.repo.ResultHorseRepo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class HorseWarningService {

    private static final int BATCH_SIZE = 500;

    private final ResultHorseRepo resultHorseRepo;
    private final HorseWarningRepo horseWarningRepo;

    @Transactional
    public void refreshWarnings(int minStarts) {
        log.info("🐴 HorseWarning: starting refresh (minStarts={})", minStarts);

        List<HorseStartSummary> bad = resultHorseRepo.findHorsesWithStartsLessThan(minStarts);
        log.info("🐴 HorseWarning: found {} horses with starts < {}", bad.size(), minStarts);

        log.info("🐴 HorseWarning: deleting old rows from kontroll...");
        horseWarningRepo.deleteAllInBatch();
        log.info("🐴 HorseWarning: delete done");

        if (bad.isEmpty()) {
            log.info("🐴 HorseWarning: nothing to save, finished");
            return;
        }

        List<HorseWarning> buffer = new ArrayList<>(Math.min(BATCH_SIZE, bad.size()));
        int saved = 0;

        for (HorseStartSummary s : bad) {
            buffer.add(HorseWarning.builder()
                    .date(s.getLastDate() == null ? 0 : s.getLastDate())
                    .name(s.getName())
                    .starts(Math.toIntExact(s.getStarts()))
                    .build());

            if (buffer.size() >= BATCH_SIZE) {
                horseWarningRepo.saveAll(buffer);
                saved += buffer.size();
                log.info("🐴 HorseWarning: saved {} / {}", saved, bad.size());
                buffer.clear();
            }
        }

        if (!buffer.isEmpty()) {
            horseWarningRepo.saveAll(buffer);
            saved += buffer.size();
        }

        log.info("🐴 HorseWarning: finished, saved {} rows to kontroll", saved);
    }
}
