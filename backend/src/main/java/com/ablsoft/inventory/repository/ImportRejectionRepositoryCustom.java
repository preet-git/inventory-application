package com.ablsoft.inventory.repository;

import com.ablsoft.inventory.model.RejectedRow;
import java.util.List;

public interface ImportRejectionRepositoryCustom {

    void insertAll(long importId, List<RejectedRow> rejections);
}
