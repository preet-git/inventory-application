package com.ablsoft.inventory.repository;

import com.ablsoft.inventory.entity.ImportRunEntity;
import com.ablsoft.inventory.entity.ImportStatus;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ImportRunRepository extends JpaRepository<ImportRunEntity, Long> {

    List<ImportRunEntity> findByStatusIn(List<ImportStatus> statuses);

    @Modifying
    @Query("""
            update ImportRunEntity r
               set r.rowsRead = :rowsRead,
                   r.rowsImported = :rowsImported,
                   r.rowsRejected = :rowsRejected
             where r.id = :id
            """)
    void updateProgress(@Param("id") Long id,
                        @Param("rowsRead") int rowsRead,
                        @Param("rowsImported") int rowsImported,
                        @Param("rowsRejected") int rowsRejected);
}
