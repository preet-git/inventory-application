package com.ablsoft.inventory.web;

import com.ablsoft.inventory.web.dto.PageResponse;
import com.ablsoft.inventory.web.dto.ProductResponse;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Accepted products from the current import, sorted and paged.
 *
 * <p>Returns a paginated {@code List<ProductResponse>} rather than the internal keyed map,
 * because an Angular table sorts and paginates an array naturally and has no use for the
 * duplicate key — which is internal and never leaves the application.
 *
 * <p>Defaults come from the brief: page zero, size fixed at ten, sorted by {@code productSku}
 * ascending. Page size is deliberately not a parameter; a client cannot request 50,000 rows in
 * one response.
 */
@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ImportQueryService queryService;

    public ProductController(ImportQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping
    public PageResponse<ProductResponse> products(
            @RequestParam(value = "page", defaultValue = "0") @Min(0) int page,
            @RequestParam(value = "sortBy", defaultValue = "productSku") String sortBy,
            @RequestParam(value = "direction", defaultValue = "ASC") String direction) {

        return queryService.products(page, sortBy, direction);
    }
}