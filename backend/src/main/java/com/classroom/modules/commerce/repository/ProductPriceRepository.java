package com.classroom.modules.commerce.repository;

import com.classroom.modules.commerce.model.ProductPrice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ProductPriceRepository extends JpaRepository<ProductPrice, String> {
    Optional<ProductPrice> findByProductId(String productId);
}
