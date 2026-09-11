package com.ablsoft.inventory.service;

import com.ablsoft.inventory.exception.ImportException;
import com.ablsoft.inventory.entity.ImportRunEntity;
import com.ablsoft.inventory.entity.ImportStatus;
import com.ablsoft.inventory.model.ImportResult;
import com.ablsoft.inventory.model.Product;
import com.ablsoft.inventory.model.RawRow;
import com.ablsoft.inventory.model.RejectedRow;
import com.ablsoft.inventory.read.FileType;
import com.ablsoft.inventory.read.RowReader;
import com.ablsoft.inventory.repository.ImportRejectionRepository;
import com.ablsoft.inventory.repository.ImportRunRepository;
import com.ablsoft.inventory.validate.RowValidator;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class ImportService {

    private static final Logger log = LoggerFactory.getLogger(ImportService.class);

    private static final int CHUNK_SIZE = 1_000;

    private static final int INLINE_REJECTION_LIMIT = 100;

    private final ImportRunRepository runs;
    private final ImportRejectionRepository rejections;
    private final ImportChunkWriter writer;
    private final TaskExecutor executor;
    private final RowValidator validator = new RowValidator();

    public ImportService(ImportRunRepository runs,
                         ImportRejectionRepository rejections,
                         ImportChunkWriter writer,
                         @Qualifier("importExecutor") TaskExecutor executor) {
        this.runs = runs;
        this.rejections = rejections;
        this.writer = writer;
        this.executor = executor;
    }

    public ImportResult status(long importId) {
        ImportRunEntity run = runs.findById(importId)
                .orElseThrow(() -> ImportException.notFound("No import with id " + importId));
        List<Integer> rowNumbers = rejectedRowNumbers(importId, 0, INLINE_REJECTION_LIMIT);
        return ImportResult.of(run, rowNumbers, run.getRowsRejected() > rowNumbers.size());
    }

    public List<Integer> rejectedRowNumbers(long importId, int page, int size) {
        return rejections
                .findByImportIdOrderByRowNumberAsc(importId,
                        PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 1_000)))
                .stream()
                .map(rejection -> rejection.getRowNumber())
                .toList();
    }

    public ImportResult submit(MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            throw ImportException.invalidFile("Uploaded file is empty.");
        }

        Path staged = stage(file);
        ImportRunEntity run;
        FileType type;
        try {
            // Fail here rather than on a worker: a client that sent the wrong kind of file should
            // be told so in the response to the upload, not by polling a job that failed.
            type = FileType.detect(file.getOriginalFilename(), staged);
            run = runs.save(new ImportRunEntity(file.getOriginalFilename(), LocalDate.now()));
        } catch (RuntimeException | IOException e) {
            deleteQuietly(staged);
            throw e;
        }

        long importId = run.getId();
        LocalDate referenceDate = run.getReferenceDate();
        try {
            executor.execute(() -> runImport(importId, staged, type, referenceDate));
        } catch (RejectedExecutionException e) {
            deleteQuietly(staged);
            throw ImportException.busy(
                    "Too many imports are already running. Try again when one has finished.");
        }
        return ImportResult.of(run, List.of(), false);
    }

    private static final class Tally {
        final List<Product> pending = new ArrayList<>(CHUNK_SIZE);
        final List<RejectedRow> rejections = new ArrayList<>();
        final Set<String> seenKeys = new HashSet<>();
        int rowsRead;
        int rowsImported;
        int rowsRejected;
    }

    private void runImport(long importId, Path staged, FileType type, LocalDate referenceDate) {
        log.info("Import {} starting: {}", importId, staged.getFileName());
        Tally tally = new Tally();
        try {
            writer.markRunning(importId);

            try (RowReader reader = RowReader.open(staged, type)) {
                reader.forEachRow(row -> accept(row, tally, importId));
            }
            flush(tally, importId);

            writer.markCompleted(importId, tally.rowsRead, tally.rowsImported, tally.rowsRejected);
            log.info("Import {} finished: {} rows read, {} imported, {} rejected",
                    importId, tally.rowsRead, tally.rowsImported, tally.rowsRejected);

        } catch (Throwable e) {
            // Throwable, not Exception: an Error here (a missing class, OOM on a huge sheet) would
            // otherwise kill the worker silently and strand the run in RUNNING forever, with no way
            // for a client polling /api/imports/{id} to learn that it died.
            log.warn("Import {} failed after {} rows", importId, tally.rowsRead, e);
            writer.markFailed(importId, describe(e));
        } finally {
            deleteQuietly(staged);
        }
    }

    private static String describe(Throwable e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    private void accept(RawRow row, Tally tally, long importId) {
        tally.rowsRead++;

        RowValidator.Result result = validator.validate(row);
        if (!result.accepted()) {
            reject(tally, row.rowNumber(), String.join("; ", result.reasons()));
        } else {
            Product product = result.product();
            if (tally.seenKeys.add(product.uniqueKey())) {
                tally.pending.add(product);
                tally.rowsImported++;
            } else {
                // Two rows in one file claiming the same SKU and date. The first won; this one is
                // reported. Removing it here also keeps a single upsert statement from touching the
                // same row twice, which Postgres refuses.
                reject(tally, product.rowNumber(), "Duplicate Product SKU and Purchase Date in this file");
            }
        }

        if (tally.pending.size() >= CHUNK_SIZE) {
            flush(tally, importId);
        }
    }

    private static void reject(Tally tally, int rowNumber, String reason) {
        tally.rejections.add(new RejectedRow(rowNumber, reason));
        tally.rowsRejected++;
        log.debug("Row {} rejected: {}", rowNumber, reason);
    }

    private void flush(Tally tally, long importId) {
        if (tally.pending.isEmpty() && tally.rejections.isEmpty()) {
            return;
        }
        writer.writeChunk(importId, tally.pending, tally.rejections,
                tally.rowsRead, tally.rowsImported, tally.rowsRejected);
        tally.pending.clear();
        tally.rejections.clear();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void failInterruptedImports() {
        List<ImportRunEntity> stale = runs.findByStatusIn(List.of(ImportStatus.PENDING, ImportStatus.RUNNING));
        for (ImportRunEntity run : stale) {
            writer.markFailed(run.getId(), "Interrupted by a restart; some rows may already be imported.");
            log.warn("Import {} was left {} by a previous run; marked FAILED", run.getId(), run.getStatus());
        }
    }

    private static Path stage(MultipartFile file) throws IOException {
        Path staged = Files.createTempFile("ablsoft-import-", suffixOf(file.getOriginalFilename()));
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, staged, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            deleteQuietly(staged);
            throw e;
        }
        return staged;
    }

    private static String suffixOf(String originalFileName) {
        if (originalFileName == null) {
            return ".upload";
        }
        int dot = originalFileName.lastIndexOf('.');
        if (dot < 0 || dot == originalFileName.length() - 1) {
            return ".upload";
        }
        String suffix = originalFileName.substring(dot);
        // The real extension check happens later; this only keeps the temp name harmless.
        return suffix.matches("\\.[A-Za-z0-9]{1,10}") ? suffix : ".upload";
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Could not delete staged upload {}", path, e);
        }
    }
}
