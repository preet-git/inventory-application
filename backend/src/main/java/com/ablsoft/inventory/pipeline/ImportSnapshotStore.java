package com.ablsoft.inventory.pipeline;

import com.ablsoft.inventory.model.ImportSnapshot;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Holds the one committed import, and is the entire "Atomic In-Memory Commit" step.
 *
 * <p>An {@link AtomicReference} to an immutable snapshot gives the atomicity the brief asks for
 * with no locking anywhere: {@link #replace} is a single reference assignment, and readers see
 * either the whole previous import or the whole new one, never a mixture. Readers never block,
 * so a long import cannot stall the dashboard.
 *
 * <p>This class is deliberately the only mutable state in the application, and it is one field.
 * When the enterprise version commits to a database instead, {@link #replace} is the method that
 * becomes a transaction — nothing else in the pipeline needs to know.
 */
public class ImportSnapshotStore {

    private final AtomicReference<ImportSnapshot> current =
            new AtomicReference<>(ImportSnapshot.empty());

    /** The active snapshot. Never null; before the first import this is the empty snapshot. */
    public ImportSnapshot current() {
        return current.get();
    }

    /**
     * Publishes a completed import. Called once per successful import, only after the entire
     * result has been built locally — a failed import never reaches this method, which is what
     * leaves the previous snapshot serving traffic.
     */
    public void replace(ImportSnapshot snapshot) {
        current.set(snapshot);
    }
}