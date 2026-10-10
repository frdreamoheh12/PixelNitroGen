# AstraCurrencyEngine

A configurable multi-currency economy plugin for **Paper 1.21.11**, built for Java 21 bytecode. The distribution version is named `1.21.11-26.2` as requested, but that filename/version label is **not a verified compatibility claim for Minecraft/Paper 26.2**. The only Paper API target configured in this project is 1.21.11. Use the compatibility matrix below and the build report rather than assuming a single JAR works on future server APIs.

Authors credited by the supplied development prompt: **LivingCritz, DevAstrix — AstraStudio**.

## Current implementation

Implemented in this source tree:

- Separate, validated `currencies/<id>.yml` definitions, with Coins and Shards installed on first start.
- Exact `BigDecimal` arithmetic, configurable precision/limits, percentage transfer tax, and a SQLite ledger.
- Asynchronous storage work on a single bounded queue; transfer/adjustment changes are committed atomically.
- UUID-based player accounts, last-known names, balance queries, deposits/withdrawals/sets, payments, top balances, rank, history, and backups.
- Player and administrative commands, tab completion, permission checks, YAML editing, explicit `confirm` arguments for destructive actions, and a basic YAML-configured inventory dashboard.
- Adventure/MiniMessage rendering with legacy color fallback; optional PlaceholderAPI expansion backed only by cached snapshots.
- A documented Java service API, Kotlin helper extensions, post-commit Bukkit event, tests for decimal rules and SQLite transaction behavior.

Not implemented in this release: MySQL/MariaDB, Vault, Skript, ajLeaderboards/Topper direct adapters, native Paper Dialog screens, admin/editor GUIs, FancyDialog, Folia support, transaction retry throttling, and a verified Paper 26.2 runtime. These are not represented as working integrations. PlaceholderAPI is optional; if absent, the plugin runs standalone.

## Requirements and installation

- Paper **1.21.11** (the Maven API target in `pom.xml`).
- Java **21 or newer**. Source/target bytecode is Java 21; Java 25 runtime compatibility is expected by the class-file level but has not been runtime-tested here.
- Maven 3.9+ to build from source.

Build with:

```bash
mvn -B clean verify
```

The deployable shaded plugin is produced at:

```text
target/AstraCurrencyEngine-1.21.11-26.2.jar
```

Copy that JAR into the Paper server's `plugins/` directory and restart. Do not use `/reload` to upgrade plugins.

The release packaging script validates the JAR descriptor and packaged SQLite driver and then creates the requested ZIPs:

```bash
python3 scripts/package_release.py
```

A GitHub Actions workflow runs Maven verification and publishes these files as a workflow artifact when the branch is built. A binary JAR must not be considered verified until that build succeeds and the resulting JAR has been inspected.

## Player commands

| Command | Permission | Description |
|---|---|---|
| `/currency` or `/currency gui` | `astracurrency.gui` | Open the currency dashboard |
| `/currency help` | `astracurrency.help` | Show help |
| `/currency list` | `astracurrency.list` | List enabled currencies |
| `/currency balance [currency] [player]` or `/currency bal ...` | `astracurrency.balance`; other player also needs `.balance.others` | Show a balance. With no args, uses the configured primary currency. |
| `/currency pay <player> <currency> <amount>` | `astracurrency.pay` | Transfer to an online or locally-known offline account |
| `/currency top <currency> [page] [10\|50\|100]` | `astracurrency.top` | Show an exact-decimal leaderboard |
| `/currency history <currency> [page]` | `astracurrency.history` | Show the caller's recent transaction records |
| `/currency info <currency>` | `astracurrency.balance` | Show currency details |
| `/astracurrency ...` | Same as `/currency` | Alias |

An offline player can be resolved by UUID or a last-known name recorded by this plugin. Unknown names are intentionally not fetched from Mojang synchronously.

## Administrator commands

All commands are under `/ace admin ...` (also available under `/currency admin ...`). Each action additionally accepts the general `astracurrency.admin` permission.

```text
/ace admin help
/ace admin create <id> [display name]
/ace admin delete <currency> confirm
/ace admin edit <currency> <property> <value>
/ace admin give <player> <currency> <amount> [reason]
/ace admin take <player> <currency> <amount> [reason]
/ace admin set <player> <currency> <amount> [reason]
/ace admin reset <player> <currency> confirm
/ace admin resetall <currency> confirm
/ace admin balance <player> <currency>
/ace admin list
/ace admin info <currency>
/ace admin reload
/ace admin backup
/ace admin restore <backup-id> confirm
/ace admin migrate
/ace admin debug
/ace admin status
```

Currency edits accept the keys in `AdminCommand.EDITABLE_PROPERTIES`; use the file-based currency YAML for other settings. Changing decimal precision is rejected when any live balance would lose precision. `/ace admin reload` reloads general/messages/menu/dialog/currency YAML only; storage settings require a restart. SQLite backups are copied after a WAL checkpoint. Currency deletion purges its active balances but retains transaction history.

## Configuration and data

On first startup, the plugin creates:

```text
plugins/AstraCurrencyEngine/
├── config.yml
├── messages.yml
├── menus.yml
├── dialogs.yml
├── storage.yml
├── integrations.yml
├── permissions.yml
├── currencies/coins.yml
├── currencies/shards.yml
├── data/astracurrency.db
├── backups/
└── logs/economy-YYYY-MM-DD.log
```

- `config.yml`: primary currency, leaderboard refresh, general options.
- `currencies/<id>.yml`: per-currency identity, precision, limits, tax percent, transfer rule, display, and menu material/slot.
- `storage.yml`: SQLite file, queue size, busy timeout. Unsupported backends fail startup rather than silently storing elsewhere.
- `messages.yml`: MiniMessage strings and command text.
- `menus.yml`: dashboard title, rows, filler, and item lore.
- `integrations.yml`: optional PlaceholderAPI toggle.
- `permissions.yml`: human-readable permission reference; `plugin.yml` supplies Bukkit defaults.
- `dialogs.yml`: confirmation text reference. Destructive actions currently use command-line confirmation, not a native dialog.

Amounts are stored as plain decimal text, and no money calculation uses `float` or `double`. `transaction-tax` is a percentage from 0 to 100, rounded down to the currency's configured precision. In a transfer of 10 Coins with a 10% fee, the sender is debited 11 Coins and the receiver gets 10 Coins.

## Permissions

The complete registered defaults are in `plugin.yml`; admin permissions default to operators. Main nodes include:

```text
astracurrency.use
astracurrency.help
astracurrency.list
astracurrency.balance
astracurrency.balance.others
astracurrency.pay
astracurrency.top
astracurrency.history
astracurrency.gui
astracurrency.currency.*
astracurrency.admin
astracurrency.admin.create|delete|edit|give|take|set|reset|resetall|reload|backup|restore|migrate|debug|status
astracurrency.*
```

Currency-specific overrides such as `astracurrency.pay.coins` and `astracurrency.balance.shards` are recognized. Grant or deny those nodes with your permission manager; a direct per-currency assignment takes precedence over the broad action node. LuckPerms itself is not a hard dependency.

## PlaceholderAPI

When PlaceholderAPI is present and enabled in `integrations.yml`, the `astracurrency` expansion registers these forms:

```text
%astracurrency_coins_balance%
%astracurrency_coins_balance_formatted%
%astracurrency_currency_coins_symbol%
%astracurrency_currency_coins_name%
%astracurrency_currency_coins_top_1_name%
%astracurrency_currency_coins_top_1_balance%
%astracurrency_currency_coins_top_1_formatted%
%astracurrency_currency_coins_rank%
%astracurrency_currency_coins_total%
%astracurrency_currency_coins_enabled%
```

Balance and leaderboard placeholders return cached snapshots only; they never wait on SQLite from a placeholder request. An empty value means that account/snapshot has not been warmed yet. Online balances are warmed on join, and leaderboards refresh periodically. The legacy misspellings requested in the development prompt are preserved for the configured primary currency:

```text
%astracurrency_cureency_balance%
%astracurrency_currency_balance_formmated%
%astracurrency_cureency_top%
```

Raw numeric placeholders are decimal strings suitable for leaderboard integrations.

## Public API

The plugin registers `AstraCurrencyAPI` with Bukkit's `ServicesManager`. Fetch the provider using:

```java
RegisteredServiceProvider<AstraCurrencyAPI> registration =
    Bukkit.getServicesManager().getRegistration(AstraCurrencyAPI.class);
AstraCurrencyAPI api = registration == null ? null : registration.getProvider();
```

Storage-backed methods return `CompletableFuture`; do not call `.join()` on Paper's primary thread. `CurrencyTransactionEvent` fires on the primary thread after commit and cannot cancel an already-completed mutation. Kotlin helpers are in `src/main/kotlin/.../AstraCurrencyExtensions.kt`.

## Build, tests, and compatibility status

The project targets `io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT` and Java 21. No compile, unit-test, plugin startup, Java 25, Paper 1.21.11, or Paper 26.2 result is claimed until recorded in `BUILD-REPORT.md`. This execution environment initially had no JDK or Maven installed; inspect that report for the actual build result.

To build in CI, GitHub Actions installs Temurin 21 and runs `mvn -B clean verify`. A successful compile/test is not the same as a server smoke test. Compatibility beyond Paper 1.21.11 remains unverified, including Minecraft/Paper 26.2.

## License

No owner-approved license was supplied. `LICENSE` records that release is pending license selection; do not redistribute under an assumed open-source or commercial license.
