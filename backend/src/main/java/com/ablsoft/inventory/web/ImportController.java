package com.ablsoft.inventory.web;

import com.ablsoft.inventory.config.ImportProperties;
import com.ablsoft.inventory.exception.ImportException;
import com.ablsoft.inventory.model.ImportSnapshot;
import com.ablsoft.inventory.model.SupportedDateFormat;
import com.ablsoft.inventory.pipeline.ProductImportService;
import com.ablsoft.inventory.read.UploadFormatValidator;
import com.ablsoft.inventory.read.UploadedFile;
import com.ablsoft.inventory.web.dto.ImportResultResponse;
import com.ablsoft.inventory.web.dto.ImportSummaryResponse;
import com.ablsoft.inventory.web.dto.PageResponse;
import com.ablsoft.inventory.web.dto.RejectedRowResponse;
import jakarta.validation.constraints.Min;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Upload endpoint and the two read endpoints for the current import.
 *
 * <p>With {@code spring.threads.virtual.enabled=true} each request runs on its own virtual
 * thread, so an import can hold its request for the duration of a large file without consuming
 * one of a small pool of platform threads, and the dashboard endpoints stay responsive.
 *
 * <p>The import runs synchronously. A caller gets the full result in the response rather than a
 * job id to poll, which is what the brief's return list implies, and a concurrent attempt is
 * refused with 409 instead of queueing behind it.
 */
@RestController
@RequestMapping("/api/imports")
public class ImportController {

    private static final Logger log = LoggerFactory.getLogger(ImportController.class);

    private final UploadFormatValidator formatValidator;
    private final ProductImportService importService;
    private final ImportQueryService queryService;
    private final ImportProperties properties;

    public ImportController(
            UploadFormatValidator formatValidator,
            ProductImportService importService,
            ImportQueryService queryService,
            ImportProperties properties) {
        this.formatValidator = formatValidator;
        this.importService = importService;
        this.queryService = queryService;
        this.properties = properties;
    }

    /**
     * @param dateFormat one of the three supported patterns; defaults to the configured
     *                   {@code MM/dd/yyyy} when omitted
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImportResultResponse importFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "dateFormat", required = false) String dateFormat) throws IOException {

        if (file.isEmpty()) {
            throw new ImportException.InvalidFile("Uploaded file is empty");
        }

        // Resolved before staging the file, so an unsupported pattern fails immediately rather
        // than after a 15 MiB copy.
        SupportedDateFormat format = dateFormat == null || dateFormat.isBlank()
                ? properties.defaultFormat()
                : SupportedDateFormat.fromPattern(dateFormat);

        Path staged = stage(file);
        try {
            UploadedFile upload = formatValidator.validate(
                    staged, file.getOriginalFilename(), file.getContentType());

            ImportSnapshot snapshot = importService.runImport(upload, format);
            return ImportResultResponse.from(snapshot);
        } finally {
            deleteQuietly(staged);
        }
    }

    @GetMapping("/current/summary")
    public ImportSummaryResponse summary() {
        return queryService.summary();
    }

    @GetMapping("/current/rejections")
    public PageResponse<RejectedRowResponse> rejections(
            @RequestParam(value = "page", defaultValue = "0") @Min(0) int page) {
        return queryService.rejections(page);
    }

    /**
     * Copies the upload to a temp file.
     *
     * <p>Needed because both the magic-number check and the parser must read the bytes, and
     * because POI needs a {@code File}. With {@code file-size-threshold: 0B} Tomcat has already
     * spooled the part to disk, so this is a disk-to-disk copy rather than a trip through the
     * heap. The name is sanitised to a suffix only — a client-supplied filename is never used to
     * build a path.
     */
    private Path stage(MultipartFile file) throws IOException {
        String suffix = suffixOf(file.getOriginalFilename());
        Path staged = Files.createTempFile("ablsoft-import-", suffix);
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, staged, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            deleteQuietly(staged);
            throw e;
        }
        return staged;
    }

    private static String suffixOf(String originalFileName) {
        if (originalFileName == null) {
            return ".upload";
        }
        int dot = originalFileName.lastIndexOf('.');
        if (dot < 0 || dot == originalFileName.length() - 1) {
            return ".upload";
        }
        String suffix = originalFileName.substring(dot);
        // Extension validation happens later; this only keeps the temp name harmless.
        return suffix.matches("\\.[A-Za-z0-9]{1,10}") ? suffix : ".upload";
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Could not delete staged upload {}", path, e);
        }
    }
}