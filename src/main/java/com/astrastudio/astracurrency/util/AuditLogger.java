package com.astrastudio.astracurrency.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/** Small append-only audit log for committed economy changes. Calls are made off the server thread. */
public final class AuditLogger {
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd")
            .withZone(ZoneOffset.UTC);
    private final Path directory;

    public AuditLogger(Path directory) throws IOException {
        this.directory = Objects.requireNonNull(directory, "directory");
        Files.createDirectories(directory);
    }

    public synchronized void transaction(String type, String currency, String sender, String recipient,
                                         String amount, String tax, String reason) throws IOException {
        String cleanReason = reason == null ? "" : reason.replace('\n', ' ').replace('\r', ' ');
        String line = String.join("\t", Instant.now().toString(), safe(type), safe(currency), safe(sender),
                safe(recipient), safe(amount), safe(tax), safe(cleanReason)) + System.lineSeparator();
        Path file = directory.resolve("economy-" + DAY.format(Instant.now()) + ".log");
        Files.writeString(file, line, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
    }

    private static String safe(String value) {
        return value == null ? "-" : value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }
}
