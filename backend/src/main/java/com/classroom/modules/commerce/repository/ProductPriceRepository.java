package com.classroom.modules.commerce.repository;

import com.classroom.modules.commerce.model.ProductPrice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface ProductPriceRepository extends JpaRepository<ProductPrice, String> {
    Optional<ProductPrice> findByProductId(String productId);

    /** D-19: the prices of a page of class-access products in one query (class listing). */
    List<ProductPrice> findByProductIdIn(Collection<String> productIds);
}
