package com.astrastudio.astracurrency.storage;

import com.astrastudio.astracurrency.api.LeaderboardEntry;
import com.astrastudio.astracurrency.api.TransactionRecord;
import com.astrastudio.astracurrency.api.TransactionResult;
import com.astrastudio.astracurrency.api.TransactionStatus;
import com.astrastudio.astracurrency.currency.CurrencyDefinition;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SQLite storage adapter. Every storage operation runs on one bounded worker, and every money
 * mutation uses a JDBC transaction. Values are persisted as decimal strings, never floating point.
 */
public final class SqliteStorage implements AutoCloseable {
    private static final DateTimeFormatter BACKUP_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
            .withZone(ZoneOffset.UTC);
    private static final Comparator<RawLeaderboardEntry> LEADERBOARD_ORDER = Comparator
            .comparing(RawLeaderboardEntry::balance).reversed()
            .thenComparing(RawLeaderboardEntry::name, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(entry -> entry.playerId().toString());

    public enum Adjustment {
        DEPOSIT,
        WITHDRAW,
        SET
    }

    private final Path databaseFile;
    private final Path backupDirectory;
    private final int busyTimeoutMillis;
    private final ThreadPoolExecutor worker;

    public SqliteStorage(Path databaseFile, Path backupDirectory, int busyTimeoutMillis, int queueCapacity) {
        this.databaseFile = Objects.requireNonNull(databaseFile, "databaseFile").toAbsolutePath().normalize();
        this.backupDirectory = Objects.requireNonNull(backupDirectory, "backupDirectory").toAbsolutePath().normalize();
        this.busyTimeoutMillis = Math.max(100, busyTimeoutMillis);
        int boundedCapacity = Math.max(32, queueCapacity);
        AtomicInteger sequence = new AtomicInteger();
        ThreadFactory factory = task -> {
            Thread thread = new Thread(task, "AstraCurrency-Storage-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        this.worker = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(boundedCapacity), factory, new ThreadPoolExecutor.AbortPolicy());
    }

    public void initialize() throws IOException, SQLException {
        try {
            Class.forName("org.sqlite.JDBC", true, SqliteStorage.class.getClassLoader());
        } catch (ClassNotFoundException ex) {
            throw new SQLException("The SQLite JDBC driver is missing from the plugin JAR", ex);
        }
        Files.createDirectories(databaseFile.getParent());
        Files.createDirectories(backupDirectory);
        try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=NORMAL");
            statement.execute("CREATE TABLE IF NOT EXISTS players ("
                    + "player_uuid TEXT PRIMARY KEY NOT NULL, last_name TEXT, last_seen INTEGER NOT NULL DEFAULT 0)");
            statement.execute("CREATE TABLE IF NOT EXISTS balances ("
                    + "player_uuid TEXT NOT NULL, currency_id TEXT NOT NULL, amount TEXT NOT NULL, updated_at INTEGER NOT NULL, "
                    + "PRIMARY KEY(player_uuid, currency_id))");
            statement.execute("CREATE TABLE IF NOT EXISTS transactions ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, request_id TEXT NOT NULL UNIQUE, type TEXT NOT NULL, "
                    + "currency_id TEXT NOT NULL, sender_uuid TEXT, recipient_uuid TEXT, amount TEXT NOT NULL, "
                    + "tax TEXT NOT NULL DEFAULT '0', reason TEXT NOT NULL DEFAULT '', created_at INTEGER NOT NULL)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_transactions_sender "
                    + "ON transactions(currency_id, sender_uuid, id DESC)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_transactions_recipient "
                    + "ON transactions(currency_id, recipient_uuid, id DESC)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_balances_currency ON balances(currency_id)");
            statement.execute("PRAGMA user_version=1");
        }
    }

    public CompletableFuture<BigDecimal> getBalance(UUID playerId, CurrencyDefinition currency) {
        return submit(() -> {
            try (Connection connection = openConnection()) {
                ensureBalance(connection, playerId, currency);
                return selectBalance(connection, playerId, currency.id());
            }
        });
    }

    public CompletableFuture<Void> rememberPlayer(UUID playerId, String name) {
        return submit(() -> {
            try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO players(player_uuid,last_name,last_seen) VALUES(?,?,?) "
                            + "ON CONFLICT(player_uuid) DO UPDATE SET last_name=excluded.last_name,last_seen=excluded.last_seen")) {
                statement.setString(1, playerId.toString());
                statement.setString(2, name == null ? "" : name);
                statement.setLong(3, System.currentTimeMillis());
                statement.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<java.util.Optional<UUID>> findPlayerIdByName(String name) {
        return submit(() -> {
            try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement(
                    "SELECT player_uuid FROM players WHERE last_name=? COLLATE NOCASE ORDER BY last_seen DESC LIMIT 1")) {
                statement.setString(1, name);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? java.util.Optional.of(UUID.fromString(result.getString(1))) : java.util.Optional.empty();
                }
            }
        });
    }

    public CompletableFuture<Boolean> balancesFitCurrency(CurrencyDefinition currency) {
        return submit(() -> {
            try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement(
                    "SELECT amount FROM balances WHERE currency_id=?")) {
                statement.setString(1, currency.id());
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        BigDecimal amount = new BigDecimal(result.getString(1));
                        if (amount.stripTrailingZeros().scale() > currency.decimalPlaces()
                                || !currency.isWithinLimits(amount)) {
                            return false;
                        }
                    }
                }
            }
            return true;
        });
    }

    public CompletableFuture<TransactionResult> transfer(String requestId, UUID sender, UUID recipient,
                                                         CurrencyDefinition currency, BigDecimal amount) {
        return submit(() -> transferNow(requestId, sender, recipient, currency, amount));
    }

    public CompletableFuture<TransactionResult> adjust(String requestId, UUID actor, UUID account,
                                                       CurrencyDefinition currency, BigDecimal amount,
                                                       Adjustment adjustment, String type, String reason) {
        return submit(() -> adjustNow(requestId, actor, account, currency, amount, adjustment, type, reason));
    }

    public CompletableFuture<List<LeaderboardEntry>> getTopBalances(String currencyId, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 10_000));
        return submit(() -> loadBalances(currencyId, safeLimit));
    }

    public CompletableFuture<List<LeaderboardEntry>> getLeaderboardSnapshot(String currencyId) {
        return submit(() -> loadBalances(currencyId, 100));
    }

    public CompletableFuture<Integer> getPlayerRank(UUID playerId, String currencyId) {
        return submit(() -> calculateRank(playerId, currencyId));
    }

    public CompletableFuture<List<TransactionRecord>> getHistory(UUID playerId, String currencyId, int page, int pageSize) {
        int safePage = Math.max(1, page);
        int safePageSize = Math.max(1, Math.min(pageSize, 100));
        long offset = (long) (safePage - 1) * safePageSize;
        return submit(() -> {
            List<TransactionRecord> records = new ArrayList<>();
            String sql = "SELECT id,type,currency_id,sender_uuid,recipient_uuid,amount,tax,reason,created_at "
                    + "FROM transactions WHERE currency_id=? AND (sender_uuid=? OR recipient_uuid=?) "
                    + "ORDER BY id DESC LIMIT ? OFFSET ?";
            try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, currencyId);
                statement.setString(2, playerId.toString());
                statement.setString(3, playerId.toString());
                statement.setInt(4, safePageSize);
                statement.setLong(5, offset);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        records.add(readTransaction(result));
                    }
                }
            }
            return List.copyOf(records);
        });
    }

    public CompletableFuture<BigDecimal> getTotal(String currencyId) {
        return submit(() -> {
            BigDecimal total = BigDecimal.ZERO;
            try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement(
                    "SELECT amount FROM balances WHERE currency_id=?")) {
                statement.setString(1, currencyId);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        total = total.add(new BigDecimal(result.getString(1)));
                    }
                }
            }
            return total;
        });
    }

    public CompletableFuture<Integer> resetAll(String requestId, CurrencyDefinition currency) {
        return submit(() -> {
            try (Connection connection = openConnection()) {
                connection.setAutoCommit(false);
                try {
                    List<AccountBalance> balances = new ArrayList<>();
                    try (PreparedStatement query = connection.prepareStatement(
                            "SELECT player_uuid,amount FROM balances WHERE currency_id=?")) {
                        query.setString(1, currency.id());
                        try (ResultSet result = query.executeQuery()) {
                            while (result.next()) {
                                balances.add(new AccountBalance(UUID.fromString(result.getString(1)),
                                        new BigDecimal(result.getString(2))));
                            }
                        }
                    }
                    int changed = 0;
                    long now = System.currentTimeMillis();
                    for (AccountBalance account : balances) {
                        if (account.amount().compareTo(currency.defaultBalance()) == 0) {
                            continue;
                        }
                        String perPlayerKey = requestKey(requestId + ":" + account.playerId());
                        if (hasRequest(connection, perPlayerKey)) {
                            continue;
                        }
                        writeBalance(connection, account.playerId(), currency.id(), currency.defaultBalance(), now);
                        insertTransaction(connection, perPlayerKey, "ADMIN_RESET_ALL", currency.id(),
                                null, account.playerId(), currency.defaultBalance(), BigDecimal.ZERO,
                                "resetall", now);
                        changed++;
                    }
                    connection.commit();
                    return changed;
                } catch (Exception ex) {
                    rollbackQuietly(connection);
                    throw ex;
                }
            }
        });
    }

    public CompletableFuture<Integer> deleteCurrencyBalances(String currencyId) {
        return submit(() -> {
            try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM balances WHERE currency_id=?")) {
                statement.setString(1, currencyId);
                return statement.executeUpdate();
            }
        });
    }

    public CompletableFuture<String> createBackup() {
        return submit(() -> {
            Files.createDirectories(backupDirectory);
            try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
            }
            String fileName = "AstraCurrency-" + BACKUP_TIME.format(Instant.now()) + ".db";
            Path destination = backupDirectory.resolve(fileName);
            Files.copy(databaseFile, destination, StandardCopyOption.COPY_ATTRIBUTES);
            return fileName;
        });
    }

    public CompletableFuture<Void> restoreBackup(String backupId) {
        return submit(() -> {
            String safeName = Path.of(backupId).getFileName().toString();
            if (!safeName.equals(backupId) || !safeName.matches("[A-Za-z0-9._-]+\\.db")) {
                throw new IllegalArgumentException("Invalid backup id");
            }
            Path source = backupDirectory.resolve(safeName).normalize();
            if (!source.startsWith(backupDirectory) || !Files.isRegularFile(source)) {
                throw new IllegalArgumentException("Backup does not exist");
            }
            try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
            }
            Files.deleteIfExists(Path.of(databaseFile + "-wal"));
            Files.deleteIfExists(Path.of(databaseFile + "-shm"));
            Path temporary = databaseFile.resolveSibling(databaseFile.getFileName() + ".restore-tmp");
            Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(temporary, databaseFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                Files.move(temporary, databaseFile, StandardCopyOption.REPLACE_EXISTING);
            }
            try (Connection connection = openConnection(); Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("PRAGMA quick_check")) {
                if (!result.next() || !"ok".equalsIgnoreCase(result.getString(1))) {
                    throw new SQLException("Restored database failed SQLite quick_check");
                }
            }
            return null;
        });
    }

    public int queuedTasks() {
        return worker.getQueue().size();
    }

    public Path databaseFile() {
        return databaseFile;
    }

    private TransactionResult transferNow(String rawRequestId, UUID sender, UUID recipient,
                                          CurrencyDefinition currency, BigDecimal amount) throws Exception {
        if (sender.equals(recipient)) {
            return TransactionResult.rejected(TransactionStatus.SAME_ACCOUNT, amount, null, null);
        }
        String requestId = requestKey(rawRequestId);
        BigDecimal tax = amount.multiply(currency.transactionTaxPercent())
                .divide(new BigDecimal("100"), currency.decimalPlaces(), java.math.RoundingMode.DOWN);
        BigDecimal totalDebit = amount.add(tax).setScale(currency.decimalPlaces(), java.math.RoundingMode.UNNECESSARY);

        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                if (hasRequest(connection, requestId)) {
                    rollbackQuietly(connection);
                    return TransactionResult.rejected(TransactionStatus.DUPLICATE_REQUEST, amount, null, null);
                }
                ensureBalance(connection, sender, currency);
                ensureBalance(connection, recipient, currency);
                BigDecimal senderBefore = selectBalance(connection, sender, currency.id());
                BigDecimal recipientBefore = selectBalance(connection, recipient, currency.id());
                BigDecimal senderAfter = senderBefore.subtract(totalDebit);
                BigDecimal recipientAfter = recipientBefore.add(amount);
                if (senderAfter.compareTo(currency.minimumBalance()) < 0) {
                    rollbackQuietly(connection);
                    return TransactionResult.rejected(TransactionStatus.INSUFFICIENT_FUNDS, amount, senderBefore, recipientBefore);
                }
                if (!currency.isWithinLimits(recipientAfter) || !currency.isWithinLimits(senderAfter)) {
                    rollbackQuietly(connection);
                    return TransactionResult.rejected(TransactionStatus.BALANCE_LIMIT, amount, senderBefore, recipientBefore);
                }
                long now = System.currentTimeMillis();
                writeBalance(connection, sender, currency.id(), senderAfter, now);
                writeBalance(connection, recipient, currency.id(), recipientAfter, now);
                long id = insertTransaction(connection, requestId, "TRANSFER", currency.id(), sender, recipient,
                        amount, tax, "player payment", now);
                connection.commit();
                TransactionRecord transaction = new TransactionRecord(id, "TRANSFER", currency.id(), sender, recipient,
                        amount, tax, "player payment", Instant.ofEpochMilli(now));
                return new TransactionResult(TransactionStatus.SUCCESS, amount, tax, senderAfter, recipientAfter, transaction);
            } catch (Exception ex) {
                rollbackQuietly(connection);
                if (isUniqueViolation(ex)) {
                    return TransactionResult.rejected(TransactionStatus.DUPLICATE_REQUEST, amount, null, null);
                }
                throw ex;
            }
        }
    }

    private TransactionResult adjustNow(String rawRequestId, UUID actor, UUID account, CurrencyDefinition currency,
                                        BigDecimal amount, Adjustment adjustment, String type, String reason) throws Exception {
        String requestId = requestKey(rawRequestId);
        String safeType = type == null ? adjustment.name() : type.toUpperCase(Locale.ROOT);
        String safeReason = reason == null ? "" : reason;
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                if (hasRequest(connection, requestId)) {
                    rollbackQuietly(connection);
                    return TransactionResult.rejected(TransactionStatus.DUPLICATE_REQUEST, amount, null, null);
                }
                ensureBalance(connection, account, currency);
                BigDecimal before = selectBalance(connection, account, currency.id());
                BigDecimal after = switch (adjustment) {
                    case DEPOSIT -> before.add(amount);
                    case WITHDRAW -> before.subtract(amount);
                    case SET -> amount;
                };
                after = after.setScale(currency.decimalPlaces(), java.math.RoundingMode.UNNECESSARY);
                if (after.compareTo(currency.minimumBalance()) < 0) {
                    TransactionStatus status = adjustment == Adjustment.WITHDRAW
                            ? TransactionStatus.INSUFFICIENT_FUNDS : TransactionStatus.BALANCE_LIMIT;
                    rollbackQuietly(connection);
                    return TransactionResult.rejected(status, amount, before, null);
                }
                if (after.compareTo(currency.maximumBalance()) > 0) {
                    rollbackQuietly(connection);
                    return TransactionResult.rejected(TransactionStatus.BALANCE_LIMIT, amount, before, null);
                }
                if (after.compareTo(before) == 0) {
                    rollbackQuietly(connection);
                    return TransactionResult.rejected(TransactionStatus.NO_CHANGE, amount, before, null);
                }
                long now = System.currentTimeMillis();
                writeBalance(connection, account, currency.id(), after, now);
                UUID sender = switch (adjustment) {
                    case DEPOSIT -> actor;
                    case WITHDRAW -> account;
                    case SET -> actor;
                };
                UUID recipient = switch (adjustment) {
                    case DEPOSIT, SET -> account;
                    case WITHDRAW -> actor;
                };
                long id = insertTransaction(connection, requestId, safeType, currency.id(), sender, recipient,
                        amount, BigDecimal.ZERO, safeReason, now);
                connection.commit();
                TransactionRecord transaction = new TransactionRecord(id, safeType, currency.id(), sender, recipient,
                        amount, BigDecimal.ZERO, safeReason, Instant.ofEpochMilli(now));
                return new TransactionResult(TransactionStatus.SUCCESS, amount, BigDecimal.ZERO, after, null, transaction);
            } catch (Exception ex) {
                rollbackQuietly(connection);
                if (isUniqueViolation(ex)) {
                    return TransactionResult.rejected(TransactionStatus.DUPLICATE_REQUEST, amount, null, null);
                }
                throw ex;
            }
        }
    }

    private List<LeaderboardEntry> loadBalances(String currencyId, int limit) throws SQLException {
        int safeLimit = Math.max(1, Math.min(limit, 10_000));
        PriorityQueue<RawLeaderboardEntry> best = new PriorityQueue<>(Math.min(safeLimit, 1024), LEADERBOARD_ORDER.reversed());
        String sql = "SELECT b.player_uuid,p.last_name,b.amount FROM balances b "
                + "LEFT JOIN players p ON p.player_uuid=b.player_uuid WHERE b.currency_id=?";
        try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, currencyId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    UUID id = UUID.fromString(result.getString(1));
                    String name = result.getString(2);
                    BigDecimal balance = new BigDecimal(result.getString(3));
                    RawLeaderboardEntry candidate = new RawLeaderboardEntry(
                            id, name == null || name.isBlank() ? id.toString() : name, balance);
                    if (best.size() < safeLimit) {
                        best.add(candidate);
                    } else if (LEADERBOARD_ORDER.compare(candidate, best.peek()) < 0) {
                        best.poll();
                        best.add(candidate);
                    }
                }
            }
        }
        List<RawLeaderboardEntry> raw = new ArrayList<>(best);
        raw.sort(LEADERBOARD_ORDER);
        List<LeaderboardEntry> entries = new ArrayList<>(raw.size());
        for (int i = 0; i < raw.size(); i++) {
            RawLeaderboardEntry entry = raw.get(i);
            entries.add(new LeaderboardEntry(i + 1, entry.playerId(), entry.name(), entry.balance()));
        }
        return List.copyOf(entries);
    }

    private int calculateRank(UUID playerId, String currencyId) throws SQLException {
        String sql = "SELECT b.player_uuid,p.last_name,b.amount FROM balances b "
                + "LEFT JOIN players p ON p.player_uuid=b.player_uuid WHERE b.currency_id=?";
        RawLeaderboardEntry target = null;
        int rank = 1;
        try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, currencyId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    UUID id = UUID.fromString(result.getString(1));
                    String name = result.getString(2);
                    RawLeaderboardEntry entry = new RawLeaderboardEntry(
                            id, name == null || name.isBlank() ? id.toString() : name,
                            new BigDecimal(result.getString(3)));
                    if (id.equals(playerId)) {
                        target = entry;
                        break;
                    }
                }
            }
            if (target == null) {
                return 0;
            }
            try (PreparedStatement scan = connection.prepareStatement(sql)) {
                scan.setString(1, currencyId);
                try (ResultSet result = scan.executeQuery()) {
                    while (result.next()) {
                        UUID id = UUID.fromString(result.getString(1));
                        if (id.equals(playerId)) {
                            continue;
                        }
                        String name = result.getString(2);
                        RawLeaderboardEntry candidate = new RawLeaderboardEntry(
                                id, name == null || name.isBlank() ? id.toString() : name,
                                new BigDecimal(result.getString(3)));
                        if (LEADERBOARD_ORDER.compare(candidate, target) < 0) {
                            rank++;
                        }
                    }
                }
            }
        }
        return rank;
    }

    private void ensureBalance(Connection connection, UUID playerId, CurrencyDefinition currency) throws SQLException {
        long now = System.currentTimeMillis();
        try (PreparedStatement account = connection.prepareStatement(
                "INSERT OR IGNORE INTO players(player_uuid,last_name,last_seen) VALUES(?,?,?)")) {
            account.setString(1, playerId.toString());
            account.setString(2, "");
            account.setLong(3, now);
            account.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT OR IGNORE INTO balances(player_uuid,currency_id,amount,updated_at) VALUES(?,?,?,?)")) {
            statement.setString(1, playerId.toString());
            statement.setString(2, currency.id());
            statement.setString(3, currency.defaultBalance().toPlainString());
            statement.setLong(4, now);
            statement.executeUpdate();
        }
    }

    private BigDecimal selectBalance(Connection connection, UUID playerId, String currencyId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT amount FROM balances WHERE player_uuid=? AND currency_id=?")) {
            statement.setString(1, playerId.toString());
            statement.setString(2, currencyId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("Balance row is missing after initialization");
                }
                return new BigDecimal(result.getString(1));
            }
        }
    }

    private void writeBalance(Connection connection, UUID playerId, String currencyId, BigDecimal amount, long now)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO balances(player_uuid,currency_id,amount,updated_at) VALUES(?,?,?,?) "
                        + "ON CONFLICT(player_uuid,currency_id) DO UPDATE SET amount=excluded.amount,updated_at=excluded.updated_at")) {
            statement.setString(1, playerId.toString());
            statement.setString(2, currencyId);
            statement.setString(3, amount.toPlainString());
            statement.setLong(4, now);
            statement.executeUpdate();
        }
    }

    private long insertTransaction(Connection connection, String requestId, String type, String currencyId,
                                   UUID sender, UUID recipient, BigDecimal amount, BigDecimal tax,
                                   String reason, long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO transactions(request_id,type,currency_id,sender_uuid,recipient_uuid,amount,tax,reason,created_at) "
                        + "VALUES(?,?,?,?,?,?,?,?,?)")) {
            statement.setString(1, requestId);
            statement.setString(2, type);
            statement.setString(3, currencyId);
            statement.setString(4, sender == null ? null : sender.toString());
            statement.setString(5, recipient == null ? null : recipient.toString());
            statement.setString(6, amount.toPlainString());
            statement.setString(7, tax.toPlainString());
            statement.setString(8, reason);
            statement.setLong(9, now);
            statement.executeUpdate();
        }
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SELECT last_insert_rowid()")) {
            if (!result.next()) {
                throw new SQLException("Could not read generated transaction id");
            }
            return result.getLong(1);
        }
    }

    private boolean hasRequest(Connection connection, String requestId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM transactions WHERE request_id=?")) {
            statement.setString(1, requestId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private Connection openConnection() throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
        boolean ready = false;
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout=" + busyTimeoutMillis);
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA synchronous=NORMAL");
            ready = true;
            return connection;
        } finally {
            if (!ready) {
                connection.close();
            }
        }
    }

    private TransactionRecord readTransaction(ResultSet result) throws SQLException {
        String sender = result.getString("sender_uuid");
        String recipient = result.getString("recipient_uuid");
        return new TransactionRecord(
                result.getLong("id"),
                result.getString("type"),
                result.getString("currency_id"),
                sender == null ? null : UUID.fromString(sender),
                recipient == null ? null : UUID.fromString(recipient),
                new BigDecimal(result.getString("amount")),
                new BigDecimal(result.getString("tax")),
                result.getString("reason"),
                Instant.ofEpochMilli(result.getLong("created_at"))
        );
    }

    private static String requestKey(String raw) {
        String value = raw == null || raw.isBlank() ? UUID.randomUUID().toString() : raw.trim();
        if (value.length() > 160) {
            throw new IllegalArgumentException("idempotency key must be at most 160 characters");
        }
        return value;
    }

    private static boolean isUniqueViolation(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof SQLException sql && (sql.getErrorCode() == 19
                    || (sql.getMessage() != null && sql.getMessage().toLowerCase(Locale.ROOT).contains("unique constraint")))) {
                return true;
            }
        }
        return false;
    }

    private static void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // Keep the original database error or transaction result.
        }
    }

    private <T> CompletableFuture<T> submit(CheckedSupplier<T> supplier) {
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            worker.execute(() -> {
                try {
                    future.complete(supplier.get());
                } catch (Throwable ex) {
                    future.completeExceptionally(ex);
                }
            });
        } catch (RejectedExecutionException ex) {
            future.completeExceptionally(new IllegalStateException("AstraCurrencyEngine storage queue is full or closed", ex));
        }
        return future;
    }

    @Override
    public void close() {
        worker.shutdown();
        try {
            if (!worker.awaitTermination(10, TimeUnit.SECONDS)) {
                worker.shutdownNow();
                worker.awaitTermination(2, TimeUnit.SECONDS);
            }
        } catch (InterruptedException ex) {
            worker.shutdownNow();
            Thread.currentThread().interrupt();
        } finally {
            deregisterDriver();
        }
    }

    private void deregisterDriver() {
        try {
            var drivers = DriverManager.getDrivers();
            while (drivers.hasMoreElements()) {
                var driver = drivers.nextElement();
                if (driver.getClass().getClassLoader() == SqliteStorage.class.getClassLoader()) {
                    DriverManager.deregisterDriver(driver);
                }
            }
        } catch (SQLException ignored) {
            // Shutdown should not fail if the driver is already gone.
        }
    }

    @FunctionalInterface
    private interface CheckedSupplier<T> {
        T get() throws Exception;
    }

    private record AccountBalance(UUID playerId, BigDecimal amount) {}
    private record RawLeaderboardEntry(UUID playerId, String name, BigDecimal balance) {}
}
