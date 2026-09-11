package com.ablsoft.inventory.model;

import com.ablsoft.inventory.entity.ImportRunEntity;
import com.ablsoft.inventory.entity.ImportStatus;
import java.time.Instant;
import java.util.List;

public record ImportResult(
        long importId,
        String fileName,
        ImportStatus status,
        Summary summary,
        Rejected rejectedRows) {

    public record Summary(
            int rowsRead,
            int importedCount,
            int rejectedCount,
            Instant startedAt,
            Instant finishedAt,
            String failureMessage) {
    }

    public record Rejected(int count, List<Integer> rowNumbers, boolean truncated) {
    }

    public static ImportResult of(ImportRunEntity run, List<Integer> rowNumbers, boolean truncated) {
        return new ImportResult(
                run.getId(),
                run.getFileName(),
                run.getStatus(),
                new Summary(
                        run.getRowsRead(),
                        run.getRowsImported(),
                        run.getRowsRejected(),
                        run.getStartedAt(),
                        run.getFinishedAt(),
                        run.getFailureMessage()),
                new Rejected(run.getRowsRejected(), rowNumbers, truncated));
    }
}
