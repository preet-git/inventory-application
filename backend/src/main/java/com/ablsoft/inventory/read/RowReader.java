package com.ablsoft.inventory.read;

import com.ablsoft.inventory.model.RowData;
import java.io.Closeable;
import java.io.IOException;

/**
 * Reads rows in original file order and pushes each one, already parsed and formula-evaluated,
 * to a consumer.
 *
 * <p>Single-threaded by contract: exactly one thread calls {@link #forEachRow} and then
 * {@link #close()}. That contract is what lets the Excel implementation own a POI workbook and
 * formula evaluator without synchronisation.
 *
 * <p>Push rather than pull, because both underlying libraries are naturally push-shaped and a
 * pull-style iterator would need either a buffer or a second thread to bridge the gap.
 */
public interface RowReader extends Closeable {

    void forEachRow(RowConsumer consumer) throws IOException, InterruptedException;

    /**
     * Receives one parsed row. Declares {@code InterruptedException} because the consumer is a
     * bounded queue: when the pipeline is saturated this call blocks, which is the backpressure
     * that stops parsed rows piling up in memory ahead of slower validation.
     */
    @FunctionalInterface
    interface RowConsumer {
        void accept(RowData row) throws InterruptedException;
    }
}