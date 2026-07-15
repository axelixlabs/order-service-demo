package com.example.orderservice.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Publishes the JVM process resident set size (RSS) as a Micrometer gauge, which
 * surfaces in Prometheus as {@code process_resident_memory_bytes}.
 *
 * <p>Neither Micrometer core nor Actuator expose RSS (they only report JVM heap
 * / non-heap), so we read it from Linux {@code /proc/self/statm} — field 2 is
 * the resident pages count. On non-Linux hosts the gauge reports {@code NaN} and
 * is effectively absent, which is fine because the app is deployed in a Linux
 * container.
 */
@Component
public class ProcessMemoryMetrics implements MeterBinder {

    private static final Logger log = LoggerFactory.getLogger(ProcessMemoryMetrics.class);

    /** 4 KiB is the page size on x86_64 Linux, which is what the container runs on. */
    private static final long PAGE_SIZE_BYTES = 4096L;
    private static final Path STATM = Path.of("/proc/self/statm");

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("process.resident.memory.bytes", this, ProcessMemoryMetrics::readResidentBytes)
                .description("Resident set size (RSS) of the JVM process")
                .baseUnit("bytes")
                .strongReference(true)
                .register(registry);
    }

    private double readResidentBytes() {
        try {
            String statm = Files.readString(STATM).trim();
            String[] fields = statm.split("\\s+");
            long residentPages = Long.parseLong(fields[1]);
            return (double) residentPages * PAGE_SIZE_BYTES;
        } catch (Exception e) {
            // Not on Linux, or /proc unavailable — report NaN so the series drops out.
            log.debug("Unable to read RSS from {}: {}", STATM, e.toString());
            return Double.NaN;
        }
    }
}
