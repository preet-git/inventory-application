package com.ablsoft.inventory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Entry point for the ABLSoft product inventory import service.
 *
 * <p>The application is intentionally stateless on disk: a successful import replaces an
 * in-memory snapshot, and all imported data is lost on restart. The commit step is isolated
 * behind a single atomic reference so that swapping it for a database write later touches one
 * class rather than the whole pipeline.
 *
 * <p>{@code @SpringBootApplication} composes three things: {@code @Configuration},
 * {@code @EnableAutoConfiguration}, and {@code @ComponentScan} rooted at this package. Every
 * class we write from here on lives under {@code com.ablsoft.inventory}, so component scanning
 * finds it without further configuration.
 *
 * <p>{@code @ConfigurationPropertiesScan} picks up {@code @ConfigurationProperties} types by
 * scanning, which keeps the import limits in a typed, validated class instead of scattering
 * {@code @Value} lookups through the service layer.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class InventoryApplication {

	static void main(String[] args) {
		SpringApplication.run(InventoryApplication.class, args);
	}
}