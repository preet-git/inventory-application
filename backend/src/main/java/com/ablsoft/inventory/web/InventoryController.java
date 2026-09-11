package com.ablsoft.inventory.web;

import com.ablsoft.inventory.model.ImportResult;
import com.ablsoft.inventory.model.InventorySummary;
import com.ablsoft.inventory.model.ProductPage;
import com.ablsoft.inventory.repository.ProductRepository;
import com.ablsoft.inventory.service.ImportService;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class InventoryController {

    private final ImportService importService;
    private final ProductRepository products;

    public InventoryController(ImportService importService, ProductRepository products) {
        this.importService = importService;
        this.products = products;
    }

    @PostMapping(value = "/imports", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ImportResult> importFile(@RequestParam("file") MultipartFile file)
            throws IOException {
        ImportResult accepted = importService.submit(file);
        return ResponseEntity.accepted().body(accepted);
    }

    @GetMapping("/imports/{importId}")
    public ImportResult importStatus(@PathVariable long importId) {
        return importService.status(importId);
    }

    @GetMapping("/imports/{importId}/rejections")
    public List<Integer> rejections(
            @PathVariable long importId,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "100") int size) {
        return importService.rejectedRowNumbers(importId, page, size);
    }

    @GetMapping("/products/summary")
    public InventorySummary summary() {
        return products.summary(LocalDate.now());
    }

    @GetMapping("/products")
    public ProductPage products(
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "size", defaultValue = "20") int size,
            @RequestParam(value = "sortBy", defaultValue = "productSku") String sortBy,
            @RequestParam(value = "direction", defaultValue = "ASC") String direction) {
        return products.page(cursor, size, sortBy, direction, LocalDate.now());
    }
}
