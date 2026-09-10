package com.ablsoft.inventory.pipeline;

import com.ablsoft.inventory.config.ImportProperties;
import com.ablsoft.inventory.exception.ImportException;
import com.ablsoft.inventory.model.ImportSnapshot;
import com.ablsoft.inventory.model.Product;
import com.ablsoft.inventory.model.RejectedRow;
import com.ablsoft.inventory.model.RowData;
import com.ablsoft.inventory.model.RowOutcome;
import com.ablsoft.inventory.model.SupportedDateFormat;
import com.ablsoft.inventory.read.RowReader;
import com.ablsoft.inventory.read.UploadedFile;
import com.ablsoft.inventory.validate.RowValidator;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs one import at a time through a bounded, back-pressured pipeline.
 *
 * <pre>
 * reader + formula evaluator   virtual thread; sole owner of the POI workbook
 *   -&gt; bounded parsed-row queue
 *   -&gt; dispatcher              virtual thread
 *   -&gt; validation executor     small bounded pool of platform threads (CPU work)
 *   -&gt; bounded result queue    futures in submission order
 *   -&gt; ordered coordinator     the calling thread; the only writer
 *   -&gt; local accepted map + rejection list
 *   -&gt; atomic snapshot replacement, only on success
 * </pre>
 *
 * <h2>Where virtual threads are used, and where they are not</h2>
 *
 * <p>The request, the reader and the dispatcher run on virtual threads: all three spend their
 * time blocked on I/O or on a queue, which is what virtual threads are for. There is
 * deliberately no virtual thread per spreadsheet row. A row is a memory-resident map entry plus
 * CPU-bound parsing, formula evaluation and validation; a thread per row would multiply live
 * objects rather than reduce them and would not make the CPU work finish sooner. Validation
 * therefore runs on a small pool of platform threads sized to the host's cores.
 *
 * <h2>Why ordering is structural rather than reconstructed</h2>
 *
 * <p>Futures are enqueued in submission order, which is file order, and the coordinator takes
 * them in that order and blocks on each. Workers finish in whatever order the scheduler picks;
 * the coordinator still observes rows by ascending row number, so {@code putIfAbsent} on a plain
 * {@link LinkedHashMap} makes the first occurrence of a duplicate key win deterministically. A
 * {@code ConcurrentHashMap} would prevent structural corruption but would let whichever worker
 * finished first define the winner, which is the wrong guarantee.
 */
public class ProductImportService {

    private static final Logger log = LoggerFactory.getLogger(ProductImportService.class);

    /** Sentinels, compared by identity only. */
    private static final RowData END_OF_ROWS = RowData.endOfFile();
    private static final Future<RowOutcome> END_OF_RESULTS = CompletableFuture.completedFuture(null);

    private static final int PARSED_QUEUE_CAPACITY = 1_024;
    private static final int RESULT_QUEUE_CAPACITY = 1_024;
    private static final int VALIDATION_QUEUE_CAPACITY = 256;
    private static final long OFFER_POLL_MILLIS = 50;
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 30;

    /** One permit. A second import is refused, not queued. */
    private final Semaphore importSlot = new Semaphore(1);

    private final ImportSnapshotStore snapshotStore;
    private final ImportProperties properties;
    private final Clock clock;

    public ProductImportService(ImportSnapshotStore snapshotStore, ImportProperties properties, Clock clock) {
        this.snapshotStore = snapshotStore;
        this.properties = properties;
        this.clock = clock;
    }

    public boolean importInProgress() {
        return importSlot.availablePermits() == 0;
    }

    /**
     * Imports one file and, on success, replaces the active snapshot.
     *
     * @throws ImportException.ImportAlreadyRunning if another import holds the permit
     * @throws ImportException.ImportFailed         if the import starts but cannot finish
     */
    public ImportSnapshot runImport(UploadedFile upload, SupportedDateFormat dateFormat) {
        if (!importSlot.tryAcquire()) {
            throw new ImportException.ImportAlreadyRunning();
        }
        try {
            Instant startedAt = clock.instant();
            // One "today" for the whole import, so a midnight rollover cannot make row 40,000
            // a day older than row 2.
            LocalDate referenceDate = LocalDate.now(clock);

            ImportSnapshot snapshot = execute(upload, dateFormat, startedAt, referenceDate);

            snapshotStore.replace(snapshot);   // the atomic commit; reached only on full success
            log.info("Imported {}: {} accepted, {} rejected",
                    upload.originalFileName(), snapshot.successfulImportCount(), snapshot.rejectedRowCount());
            return snapshot;

        } catch (ImportException e) {
            throw e;                            // already carries its code and message
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ImportException.ImportFailed("Import was interrupted", e);
        } catch (Exception e) {
            throw new ImportException.ImportFailed(
                    "Import of '" + upload.originalFileName() + "' failed: " + e.getMessage(), e);
        } finally {
            // Released on every path, so a crashed import cannot wedge the endpoint into a
            // permanent 409.
            importSlot.release();
        }
    }

    private ImportSnapshot execute(
            UploadedFile upload, SupportedDateFormat dateFormat, Instant startedAt, LocalDate referenceDate)
            throws Exception {

        BlockingQueue<RowData> parsedRows = new ArrayBlockingQueue<>(PARSED_QUEUE_CAPACITY);
        BlockingQueue<Future<RowOutcome>> results = new ArrayBlockingQueue<>(RESULT_QUEUE_CAPACITY);
        AtomicBoolean aborted = new AtomicBoolean(false);

        RowValidator validator = new RowValidator(dateFormat, upload.format());
        ThreadPoolExecutor validationExecutor = newValidationExecutor();
        ExecutorService stages = Executors.newVirtualThreadPerTaskExecutor();

        try {
            Future<Void> readerTask = stages.submit(readerTask(upload, parsedRows, aborted));
            Future<Void> dispatchTask =
                    stages.submit(dispatchTask(parsedRows, results, validationExecutor, validator, aborted));

            // The calling thread is the coordinator: it is the only writer, so there is no reason
            // to hand the result across another boundary.
            ImportSnapshot snapshot = coordinate(upload, dateFormat, startedAt, referenceDate, results);

            // The reader always emits its sentinel, even when it fails, so the coordinator above
            // returns normally on a partial file. Surface the real cause here instead.
            awaitStage(readerTask);
            awaitStage(dispatchTask);
            return snapshot;

        } catch (Exception e) {
            aborted.set(true);
            parsedRows.clear();
            drainAndCancel(results);
            throw e;
        } finally {
            aborted.set(true);
            validationExecutor.shutdownNow();
            stages.shutdownNow();
            stages.close();
            if (!validationExecutor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                log.warn("Validation executor did not terminate within {}s", SHUTDOWN_TIMEOUT_SECONDS);
            }
        }
    }

    // ---------------------------------------------------------------- stage 1: read

    private Callable<Void> readerTask(
            UploadedFile upload, BlockingQueue<RowData> parsedRows, AtomicBoolean aborted) {
        return () -> {
            try (RowReader reader = upload.openReader(properties.maxDataRows())) {
                reader.forEachRow(row -> {
                    if (!offerUntilAccepted(parsedRows, row, aborted)) {
                        throw new PipelineAborted();
                    }
                });
                return null;
            } finally {
                offerSentinel(parsedRows, END_OF_ROWS);
            }
        };
    }

    // ---------------------------------------------------------------- stage 2: dispatch

    private Callable<Void> dispatchTask(
            BlockingQueue<RowData> parsedRows,
            BlockingQueue<Future<RowOutcome>> results,
            ThreadPoolExecutor validationExecutor,
            RowValidator validator,
            AtomicBoolean aborted) {

        return () -> {
            try {
                while (!aborted.get()) {
                    RowData row = parsedRows.poll(OFFER_POLL_MILLIS, TimeUnit.MILLISECONDS);
                    if (row == null) {
                        continue;
                    }
                    if (row.isEndOfFile()) {
                        break;
                    }
                    RowData task = row;
                    Future<RowOutcome> result = validationExecutor.submit(() -> validator.validate(task));
                    if (!offerUntilAccepted(results, result, aborted)) {
                        result.cancel(true);
                        break;
                    }
                }
                return null;
            } finally {
                offerSentinel(results, END_OF_RESULTS);
            }
        };
    }

    private ThreadPoolExecutor newValidationExecutor() {
        int workers = properties.effectiveValidationWorkers();
        ThreadFactory threadFactory = new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger();

            @Override
            public Thread newThread(Runnable runnable) {
                // Platform threads on purpose: this pool exists to bound CPU parallelism, and
                // virtual threads would neither speed the work up nor bound it.
                Thread thread = new Thread(runnable, "import-validator-" + counter.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }
        };
        return new ThreadPoolExecutor(workers, workers, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(VALIDATION_QUEUE_CAPACITY), threadFactory, new BackpressurePolicy());
    }

    /**
     * Backpressure instead of an unbounded queue: when validation saturates, the dispatcher runs
     * the row itself, which stalls dispatch, fills the parsed-row queue and stalls the reader.
     * Safe because validation touches neither POI objects nor the coordinator's collections.
     */
    private static final class BackpressurePolicy implements RejectedExecutionHandler {
        @Override
        public void rejectedExecution(Runnable task, ThreadPoolExecutor executor) {
            if (executor.isShutdown()) {
                // Fail loudly rather than silently dropping a task whose future is awaited.
                throw new RejectedExecutionException("Validation executor is shut down");
            }
            task.run();
        }
    }

    // ---------------------------------------------------------------- stage 3: coordinate

    /**
     * Consumes results strictly in row order and is the only component that mutates the accepted
     * map and the rejection list. Single-threaded by construction, so those are plain
     * collections needing no locking.
     */
    private ImportSnapshot coordinate(
            UploadedFile upload,
            SupportedDateFormat dateFormat,
            Instant startedAt,
            LocalDate referenceDate,
            BlockingQueue<Future<RowOutcome>> results) throws InterruptedException {

        Map<String, Product> accepted = new LinkedHashMap<>();
        List<RejectedRow> rejected = new ArrayList<>();

        while (true) {
            Future<RowOutcome> future = results.take();
            if (future == END_OF_RESULTS) {
                break;
            }
            RowOutcome outcome;
            try {
                // Blocking here is what enforces order: row 7 is never applied before row 6,
                // however early its worker finished.
                outcome = future.get();
            } catch (ExecutionException e) {
                throw new ImportException.ImportFailed("Row validation failed unexpectedly", e.getCause());
            }

            switch (outcome) {
                case RowOutcome.Accepted acceptedRow -> {
                    Product product = acceptedRow.product();
                    Product winner = accepted.putIfAbsent(product.duplicateKey(), product);
                    if (winner != null) {
                        // First occurrence by row number keeps the key; this row is the duplicate.
                        rejected.add(RejectedRow.duplicate(
                                product.rowNumber(), product.productSku(),
                                product.purchaseDate(), winner.rowNumber()));
                    }
                }
                case RowOutcome.Rejected rejectedRow -> rejected.add(rejectedRow.rejection());
            }
        }

        return ImportSnapshot.commit(
                upload.originalFileName(), dateFormat, startedAt, referenceDate, accepted, rejected);
    }

    // ---------------------------------------------------------------- queue plumbing

    private static <T> boolean offerUntilAccepted(BlockingQueue<T> queue, T item, AtomicBoolean aborted)
            throws InterruptedException {
        while (!aborted.get()) {
            if (queue.offer(item, OFFER_POLL_MILLIS, TimeUnit.MILLISECONDS)) {
                return true;
            }
        }
        return false;
    }

    /** Delivers end-of-stream even while aborting, so a stage blocked on take() terminates. */
    private static <T> void offerSentinel(BlockingQueue<T> queue, T sentinel) {
        try {
            while (!queue.offer(sentinel, OFFER_POLL_MILLIS, TimeUnit.MILLISECONDS)) {
                if (Thread.currentThread().isInterrupted()) {
                    return;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void drainAndCancel(BlockingQueue<Future<RowOutcome>> results) {
        Future<RowOutcome> future;
        while ((future = results.poll()) != null) {
            if (future != END_OF_RESULTS) {
                future.cancel(true);
            }
        }
    }

    private static void awaitStage(Future<Void> stage) throws Exception {
        try {
            stage.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof PipelineAborted) {
                throw new ImportException.ImportFailed("Import aborted during processing");
            }
            if (cause instanceof Exception checked) {
                throw checked;
            }
            throw new ImportException.ImportFailed("Import stage failed", cause);
        }
    }

    /** Unwinds a stage during teardown. Never surfaces to callers. */
    private static final class PipelineAborted extends RuntimeException {
        private static final long serialVersionUID = 1L;

        PipelineAborted() {
            super("Pipeline aborted", null, false, false);
        }
    }
}