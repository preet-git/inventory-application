package com.ablsoft.inventory.repository;

import com.ablsoft.inventory.model.InventorySummary;
import com.ablsoft.inventory.model.ProductPage;
import java.time.LocalDate;

public interface ProductRepositoryCustom {

    ProductPage page(String cursor, int size, String sortBy, String direction, LocalDate asOf);

    InventorySummary summary(LocalDate asOf);
}
