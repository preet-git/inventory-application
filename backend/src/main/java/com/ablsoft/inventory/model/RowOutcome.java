package com.ablsoft.inventory.model;

/**
 * The result of validating one row: either an accepted {@link Product} or a {@link RejectedRow}.
 *
 * <p>Sealed, so the compiler knows there are exactly two cases. A {@code switch} over this type
 * needs no default branch, and adding a third outcome later becomes a compile error at every
 * site that has to handle it rather than a silently ignored case.
 *
 * <p>This is what a validation worker returns and what the coordinator consumes. Both variants
 * carry their row number so ordering is never inferred from the order work happened to finish:
 * workers run in parallel and complete out of order, but the coordinator applies results by
 * ascending row number, which is what makes duplicate resolution deterministic.
 */
public sealed interface RowOutcome {

    /** The 1-based source row this outcome came from. */
    int rowNumber();

    record Accepted(Product product) implements RowOutcome {

        public Accepted {
            if (product == null) {
                throw new IllegalArgumentException("Accepted outcome requires a product");
            }
        }

        @Override
        public int rowNumber() {
            return product.rowNumber();
        }

        public String duplicateKey() {
            return product.duplicateKey();
        }
    }

    record Rejected(RejectedRow rejection) implements RowOutcome {

        public Rejected {
            if (rejection == null) {
                throw new IllegalArgumentException("Rejected outcome requires a rejection");
            }
        }

        @Override
        public int rowNumber() {
            return rejection.rowNumber();
        }
    }
}