package com.ablsoft.inventory.repository;

import com.ablsoft.inventory.entity.ProductEntity;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductRepository extends JpaRepository<ProductEntity, Long>, ProductRepositoryCustom {

    List<ProductEntity> findByProductSkuInAndPurchaseDateIn(
            Collection<String> productSkus, Collection<LocalDate> purchaseDates);
}
