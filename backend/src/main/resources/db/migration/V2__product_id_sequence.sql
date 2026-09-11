-- Products are written with saveAll, which needs Hibernate to know each id before it flushes.
-- IDENTITY makes that impossible: Hibernate has to round-trip every insert to read the generated
-- key back, which silently disables JDBC batching no matter what hibernate.jdbc.batch_size says.
-- A sequence lets it claim a block of ids up front and batch the whole chunk in one go.
--
-- bigserial already created product_id_seq. Hibernate's pooled optimiser assumes the sequence
-- steps by exactly the allocationSize declared on ProductEntity, so the two have to be kept in
-- step: if they ever disagree, two chunks are handed overlapping id blocks and the inserts fail
-- on the primary key. Changing one means changing the other.
ALTER SEQUENCE product_id_seq INCREMENT BY 1000;
