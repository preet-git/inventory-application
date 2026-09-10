package com.ablsoft.inventory.config;

import com.ablsoft.inventory.model.SupportedDateFormat;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/**
 * Typed, validated binding for the {@code ablsoft.import.*} configuration block.
 *
 * <p>A record gives constructor binding, so the object is immutable once the context starts and
 * can be shared freely across the reader thread, the validation workers and the coordinator
 * without any synchronisation.
 *
 * <p>Every constraint here is checked while the context is starting. A misconfigured deployment
 * fails at boot with a message naming the offending property, rather than surfacing as a
 * confusing error on someone's first upload.
 */
@Validated
@ConfigurationProperties(prefix = "ablsoft.import")
public record ImportProperties(

        /*
         * Data rows excluding the header. The upper bound is not arbitrary defensiveness: a
         * limit of Integer.MAX_VALUE would silently defeat the guard it is meant to be, so the
         * ceiling keeps the property honest.
         */
        @DefaultValue("50000") @Min(1) @Max(1_000_000) int maxDataRows,

        /* Service-level size guard. Mirrors spring.servlet.multipart.max-file-size. */
        @DefaultValue("15MB") @NotNull DataSize maxUploadSize,

        /* Applied when an upload request omits the dateFormat parameter. */
        @DefaultValue("MM/dd/yyyy") @NotBlank String defaultDateFormat,

        /* Validation worker threads. Zero means derive from the host's processor count. */
        @DefaultValue("0") @Min(0) @Max(64) int validationWorkers) {

    /**
     * Validation is CPU-bound, so more threads than cores buys nothing but context switching.
     * The cap keeps a large host from spawning a pool far wider than the work justifies.
     */
    private static final int MAX_DERIVED_WORKERS = 8;

    public ImportProperties {
        // Resolving here means an unsupported pattern is a startup failure, not a runtime one.
        SupportedDateFormat.fromPattern(defaultDateFormat);
    }

    /** The configured default, already resolved to its parser. */
    public SupportedDateFormat defaultFormat() {
        return SupportedDateFormat.fromPattern(defaultDateFormat);
    }

    public long maxUploadBytes() {
        return maxUploadSize.toBytes();
    }

    /**
     * Resolves the worker count, deriving it from the host when the property is left at zero.
     * One core is deliberately left for the reader thread, the coordinator and the HTTP layer.
     */
    public int effectiveValidationWorkers() {
        if (validationWorkers > 0) {
            return validationWorkers;
        }
        int available = Runtime.getRuntime().availableProcessors();
        return Math.max(1, Math.min(MAX_DERIVED_WORKERS, available - 1));
    }
}