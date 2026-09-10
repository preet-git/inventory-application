package com.ablsoft.inventory.config;

import com.ablsoft.inventory.pipeline.ImportSnapshotStore;
import com.ablsoft.inventory.pipeline.ProductImportService;
import com.ablsoft.inventory.read.UploadFormatValidator;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.util.unit.DataSize;

/**
 * Wires the import pipeline.
 *
 * <p>The pipeline classes are framework-free — no {@code @Service}, no field injection — so a
 * test constructs {@code new ProductImportService(store, properties, fixedClock)} directly with
 * no Spring context and no mocking framework. Assembly lives here instead.
 */
@Configuration
public class ImportConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ImportConfiguration.class);

    private static final String MULTIPART_LIMIT_PROPERTY = "spring.servlet.multipart.max-file-size";

    /**
     * The brief requires an injected {@code Clock} so stock-age behaviour is testable. Every
     * date in the application comes from here; nothing calls {@code LocalDate.now()} directly.
     */
    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }

    @Bean
    public ImportSnapshotStore importSnapshotStore() {
        return new ImportSnapshotStore();
    }

    @Bean
    public UploadFormatValidator uploadFormatValidator(com.ablsoft.inventory.config.ImportProperties properties) {
        return new UploadFormatValidator(properties);
    }

    @Bean
    public ProductImportService productImportService(
            ImportSnapshotStore snapshotStore, com.ablsoft.inventory.config.ImportProperties properties, Clock clock, Environment environment) {

        warnIfUploadLimitsDisagree(properties, environment);
        log.info("Import pipeline ready: max {} data rows, max upload {}, {} validation workers",
                properties.maxDataRows(), properties.maxUploadSize(), properties.effectiveValidationWorkers());

        return new ProductImportService(snapshotStore, properties, clock);
    }

    /**
     * The two upload limits guard different layers: the multipart limit stops an oversized body
     * at the container, before our code runs, while the service limit is what the error message
     * quotes and what applies when the service is driven directly, as the tests do. They should
     * agree, and a mismatch is worth a startup warning rather than a silent surprise where a
     * caller is told one number by the API and rejected by another.
     */
    private void warnIfUploadLimitsDisagree(ImportProperties properties, Environment environment) {
        String configured = environment.getProperty(MULTIPART_LIMIT_PROPERTY);
        if (configured == null || configured.isBlank()) {
            return;
        }
        try {
            long multipartBytes = DataSize.parse(configured).toBytes();
            if (multipartBytes != properties.maxUploadBytes()) {
                log.warn("Upload limits disagree: {}={} but ablsoft.import.max-upload-size={}. "
                                + "The smaller limit is what callers will actually hit.",
                        MULTIPART_LIMIT_PROPERTY, configured, properties.maxUploadSize());
            }
        } catch (IllegalArgumentException e) {
            log.warn("Could not parse {}='{}' to compare against the import limit",
                    MULTIPART_LIMIT_PROPERTY, configured);
        }
    }
}