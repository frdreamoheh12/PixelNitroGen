# AstraCurrencyEngine — Master Development Prompt

**Project:** AstraCurrencyEngine  
**Version:** `1.21.11-26.2`  
**Studio:** AstraStudio  
**Authors:** LivingCritz, DevAstrix  
**Languages:** Java and Kotlin  
**Build:** Apache Maven  
**Target:** Minecraft Paper 1.21.11  
**Java:** Java 21 bytecode; validate runtime compatibility with Java 21 and Java 25

## Role and objective

Act as a principal Minecraft plugin engineer, Java/Kotlin architect, and Maven release engineer with 15+ years of experience. Build, test, compile, document, and package a **production-ready**, configurable multi-currency economy plugin for Paper called **AstraCurrencyEngine**. Deliver working source code and a verified Maven JAR—not pseudocode, a skeleton, or unimplemented stubs. Verify all third-party APIs and dependencies before implementing integrations.

## 1. Default currencies and YAML definitions

Create two default currencies on first startup: **Coins** (gold/orange gradient, `⛁` symbol, 100 default balance, 2 decimal places) and **Shards** (cyan/purple gradient, `✦` symbol, 0 default balance, integer precision). Every currency must have an independent configuration file under `plugins/AstraCurrencyEngine/currencies/<id>.yml`. Automatically load, validate, register, update, and remove currencies as administrators create, edit, disable, or delete them. Never hardcode the currency list into menus.

Provide `config.yml`, `messages.yml`, `menus.yml`, `dialogs.yml`, `storage.yml`, `integrations.yml`, and `permissions.yml`. Use `data/`, `backups/`, and `logs/` directories. Currency YAML defines identifiers, display names, singular/plural forms, symbols, descriptions, formatting, precision, limits, starting balance, transfer rules, tax, menu material, model data, permission flags, sounds, titles, leaderboard options, and integrations. Persist **player balances in a transactional database**, not individual YAML files.

Example currency definition:

```yaml
id: shards
enabled: true
display:
  name: '<gradient:#55FFFF:#AA55FF><bold>Shards</bold></gradient>'
  singular: Shard
  plural: Shards
  symbol: '✦'
formatting:
  decimals: 0
  grouping: true
  pattern: '{symbol} {amount}'
  compact-pattern: '{amount_compact} {symbol}'
  locale: en-US
  rounding-mode: DOWN
economy:
  default-balance: '0'
  minimum-balance: '0'
  maximum-balance: '1000000000000'
  transferable: true
  allow-negative: false
  transaction-tax: '0'
  leaderboard-enabled: true
menus:
  visible: true
  display-item: AMETHYST_SHARD
  slot: 11
notifications:
  title-enabled: true
  sound-enabled: true
  actionbar-enabled: true
```

## 2. Formatting, branding, and messages

Support Adventure Components, MiniMessage, hex colors, gradients, rainbow, decorations, hover/click events where supported, and legacy `&`/`§` codes. Centralize rendering, escape user-provided text to prevent MiniMessage injection, and allow configurable AstraStudio branding. Format balances using `BigDecimal`, locale grouping, custom decimals, suffixes (`K`, `M`, `B`), and customizable patterns. Support configurable chat messages, action bars, titles, subtitles, GUI text, and sounds. Include polished hex-themed default `messages.yml` and other configurations.

## 3. Economy core

Implement currency registration and discovery, UUID-based player accounts, offline balances, get/set/deposit/withdraw/reset, atomic player-to-player payments, taxes, caps, defaults, total circulation, history, and administrator adjustment reasons. Use exact decimal arithmetic only. Validate all amounts and scales; reject invalid/negative operations, overflow, unauthorized actions, insufficient balances, and duplicate transaction requests. Transfers must be all-or-nothing. Protect against concurrent duplication exploits and lost updates. Publish a documented Java API and Kotlin extensions, with explicit asynchronous threading contracts and appropriate Bukkit/Paper events.

## 4. Player commands

Register `/currency`, `/astracurrency`, and `/ace` (aliases as appropriate):

| Command | Purpose |
|---|---|
| `/currency` | Open overview |
| `/currency help` | Help |
| `/currency list` | List currencies |
| `/currency balance [currency] [player]` | View balance |
| `/currency bal [currency] [player]` | Balance alias |
| `/currency pay <player> <currency> <amount>` | Transfer funds |
| `/currency top <currency> [page]` | Leaderboard |
| `/currency history <currency> [page]` | Transaction history |
| `/currency info <currency>` | Currency information |
| `/currency gui` | Open dashboard |

Provide correct permissions, tab completion, console behavior, validation, and configurable messages.

## 5. Administrator commands

Implement `/ace admin help`, `create <currency>`, `delete <currency>`, `edit <currency> <property> <value>`, `give <player> <currency> <amount>`, `take`, `set`, `reset <player> <currency>`, `resetall <currency>`, `balance <player> <currency>`, `list`, `info`, `reload`, `backup`, `restore <backup-id>`, `migrate`, `debug`, and `status`. Require confirmation for destructive actions, audit changes, support console usage and resolvable offline UUIDs, and refresh menus/integrations after changes.

## 6. Permissions

Implement and document defaults for:

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
astracurrency.admin.create
astracurrency.admin.delete
astracurrency.admin.edit
astracurrency.admin.give
astracurrency.admin.take
astracurrency.admin.set
astracurrency.admin.reset
astracurrency.admin.resetall
astracurrency.admin.reload
astracurrency.admin.backup
astracurrency.admin.restore
astracurrency.admin.migrate
astracurrency.admin.debug
astracurrency.admin.status
astracurrency.*
```

Support currency-specific permissions such as `astracurrency.pay.coins` and `astracurrency.balance.shards`. Ordinary players must not receive admin permissions by default. Work with Bukkit/Paper permissions and LuckPerms.

## 7. PlaceholderAPI and leaderboards

Register expansion `astracurrency`. Implement dynamic placeholders, including:

```text
%astracurrency_coins_balance%
%astracurrency_coins_balance_formatted%
%astracurrency_shards_balance%
%astracurrency_shards_balance_formatted%
%astracurrency_currency_coins_symbol%
%astracurrency_currency_coins_name%
%astracurrency_currency_coins_top_1_name%
%astracurrency_currency_coins_top_1_balance%
%astracurrency_currency_coins_top_1_formatted%
%astracurrency_currency_coins_rank%
%astracurrency_currency_coins_total%
%astracurrency_currency_coins_enabled%
```

Also preserve these user-requested misspellings as documented legacy aliases, resolving against the configured primary currency where no ID is supplied:

```text
%astracurrency_cureency_balance%
%astracurrency_currency_balance_formmated%
%astracurrency_cureency_top%
```

Build leaderboard support for top 10/50/100, paging, offline UUIDs, tie-breaking, rank lookup, cached snapshots, async refresh, and **raw numeric** placeholders suitable for ajLeaderboards. Integrate with Topper and other plugins only through verified APIs or documented PlaceholderAPI interoperability. Avoid database queries on the main thread.

## 8. GUI and dialog menus

Provide a unique, branded, YAML-customizable dashboard, currency details, balance view, pay flow, leaderboards, history, admin manager, creation/editor interfaces, and confirmation dialogs. Use the verified native Paper Dialog API where available, with an inventory-GUI fallback. FancyDialog may be an optional adapter or design reference only after its API and compatibility are verified. Support configurable icons, MiniMessage, gradients, layouts, pages, sound effects, and permission-aware items. Automatically display newly created currencies. Never perform blocking storage I/O inside GUI events.

## 9. Integrations

- **Vault:** optionally expose a configurable primary currency (default Coins) through the conventional Economy provider. Document Vault's numeric precision and synchronous API limitations; keep native multi-currency operations in AstraCurrencyEngine's API.
- **Skript:** implement a verified optional bridge for get/add/remove/set/reset/transfer operations and currency events. Document actual supported Skript versions and syntax.
- **PlaceholderAPI:** full dynamic expansion and numeric leaderboard placeholders.
- **ajLeaderboards / Topper:** verified direct adapter or PlaceholderAPI-based integration; graceful fallback if absent.
- **LuckPerms:** honor normal permissions; no hard dependency.
- **Other plugins:** public Java/Kotlin API and events.

Optional dependencies must never prevent standalone plugin startup. Do not invent Maven coordinates or APIs.

## 10. Public API

Expose concrete, documented types such as `AstraCurrencyAPI`, `CurrencyService`, `CurrencyRegistry`, `BalanceService`, `TransactionService`, `TransactionResult`, `TransactionRecord`, `LeaderboardService`, and `CurrencyFormatService`. Support `getCurrency`, `getCurrencies`, `getBalance`, `deposit`, `withdraw`, `setBalance`, `transfer`, `getTopBalances`, and `getPlayerRank`. Prefer `CompletionStage`/`CompletableFuture` for storage-backed operations. Provide Java and Kotlin examples and define event thread behavior.

## 11. Storage and performance

Use SQLite by default and optionally MySQL/MariaDB. Design indexed tables for accounts, balances, transaction history, and schema migrations. Use prepared statements, atomic database transactions, safe migrations, connection management, backups, recovery, and graceful shutdown. Use HikariCP where suitable. Ensure thread-safe caches, bounded executors, leaderboard snapshots, and no main-thread blocking or uncontrolled thread creation. Do not claim Folia support without testing its scheduling requirements.

## 12. Maven project and plugin metadata

Use Maven only, with Java/Kotlin mixed-source compilation, correct plugin execution order, JUnit 5, shading/relocation of required runtime libraries, and `provided` scope for server APIs. A multi-module structure (`astra-api`, `astra-core`, `astra-paper`, `astra-integrations`) is preferred if it improves maintainability. Include a valid `plugin.yml` or Paper descriptor, command registration, permissions, optional soft dependencies, authors, and the real main class. Verify the Paper API version and Java requirements. Run:

```bash
mvn -B clean verify
```

Do not mark the build complete until the JAR exists and its plugin descriptor and packaged dependencies are inspected.

## 13. License and attribution

Include `LICENSE`, `README.md`, `CHANGELOG.md`, `CONTRIBUTING.md`, and AstraStudio credits. Do not invent a license selected by the owner: request or document the chosen license before release. An optional commercial licensing mechanism must not compromise stored player data, embed private secrets, or perform remote shutdowns.

## 14. Tests and acceptance

Use JUnit 5 and suitable mocks/integration tests for currency YAML, defaults, validation, precision, transfers, concurrency, duplicate protection, permissions, storage persistence, migrations, backups, placeholders, leaderboards, and optional integrations. Where possible, perform a Paper 1.21.11 startup smoke test. Report separately what was compiled, unit-tested, integration-tested, and not verified. Never fabricate test success.

**Acceptance conditions:** Both default currencies load; custom currency files create and register currencies; new currencies appear in GUIs; balances survive restarts; transfers are atomic; permissions work; placeholders and verified integrations resolve; database operations are safe; Java and Kotlin sources compile; Maven verification passes; documentation matches implemented commands and APIs.

## 15. Final deliverables

Produce these files:

1. `AstraCurrencyEngine-1.21.11-26.2.jar` — compiled, deployable plugin.
2. `AstraCurrencyEngine-Source.zip` — complete Maven project, source, tests, resources, configurations, documentation.
3. `AstraCurrencyEngine-Complete.zip` — compiled plugin, source, examples, documentation, license, and changelog.
4. `README.md` — installation, requirements, configuration, commands, permissions, placeholders, integrations, examples, troubleshooting, and limitations.
5. `LICENSE` — actual owner-approved license.
6. `BUILD-REPORT.md` — Maven result, test results, file sizes, SHA-256 checksums, dependency versions, verified integrations, and outstanding limitations.

## 16. Mandatory execution sequence

1. Verify Minecraft/Paper, Java, Maven, and integration APIs.
2. Design architecture, schema, and public contracts.
3. Implement core economy, persistence, and YAML configuration.
4. Implement player/admin commands, permissions, and messages.
5. Implement dynamic GUI/dialog workflows.
6. Implement PlaceholderAPI, Vault, Skript, and verified leaderboard integrations.
7. Implement tests and fix all build failures.
8. Compile and inspect the final JAR.
9. Package source and distribution ZIPs.
10. Supply all artifacts with accurate verification results.

**Strict rules:** No pseudocode, TODO-only methods, fake integrations, invented dependencies, invalid APIs, unsafe Bukkit threading, floating-point balances, hardcoded GUI currencies, omitted requested placeholder aliases, unverified compatibility claims, or fabricated deliverables. Clearly identify any feature that cannot be verified and why.

**Final directive:** Build a genuine, reliable, extensible, premium-quality multi-currency economy engine for AstraStudio. Continue through implementation, testing, Maven packaging, and documentation rather than stopping at a plan.
