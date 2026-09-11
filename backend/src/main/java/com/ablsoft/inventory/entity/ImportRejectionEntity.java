package com.ablsoft.inventory.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;

@Entity
@Table(name = "import_rejection")
@IdClass(ImportRejectionEntity.Key.class)
public class ImportRejectionEntity {

    @Id
    @Column(name = "import_id")
    private Long importId;

    @Id
    @Column(name = "row_number")
    private Integer rowNumber;

    @Column
    private String reason;

    protected ImportRejectionEntity() {
        // for JPA
    }

    public Long getImportId() {
        return importId;
    }

    public Integer getRowNumber() {
        return rowNumber;
    }

    public String getReason() {
        return reason;
    }

    public record Key(Long importId, Integer rowNumber) implements Serializable {

        private static final long serialVersionUID = 1L;

        public Key {
            Objects.requireNonNull(importId);
            Objects.requireNonNull(rowNumber);
        }
    }
}
