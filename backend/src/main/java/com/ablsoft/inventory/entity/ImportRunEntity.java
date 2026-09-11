package com.ablsoft.inventory.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "import_run")
public class ImportRunEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ImportStatus status;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "reference_date", nullable = false)
    private LocalDate referenceDate;

    @Column(name = "rows_read", nullable = false)
    private int rowsRead;

    @Column(name = "rows_imported", nullable = false)
    private int rowsImported;

    @Column(name = "rows_rejected", nullable = false)
    private int rowsRejected;

    @Column(name = "failure_message")
    private String failureMessage;

    protected ImportRunEntity() {
        // for JPA
    }

    public ImportRunEntity(String fileName, LocalDate referenceDate) {
        this.fileName = fileName;
        this.referenceDate = referenceDate;
        this.status = ImportStatus.PENDING;
        this.startedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getFileName() {
        return fileName;
    }

    public ImportStatus getStatus() {
        return status;
    }

    public void setStatus(ImportStatus status) {
        this.status = status;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
    }

    public LocalDate getReferenceDate() {
        return referenceDate;
    }

    public int getRowsRead() {
        return rowsRead;
    }

    public void setRowsRead(int rowsRead) {
        this.rowsRead = rowsRead;
    }

    public int getRowsImported() {
        return rowsImported;
    }

    public void setRowsImported(int rowsImported) {
        this.rowsImported = rowsImported;
    }

    public int getRowsRejected() {
        return rowsRejected;
    }

    public void setRowsRejected(int rowsRejected) {
        this.rowsRejected = rowsRejected;
    }

    public String getFailureMessage() {
        return failureMessage;
    }

    public void setFailureMessage(String failureMessage) {
        this.failureMessage = failureMessage;
    }
}
