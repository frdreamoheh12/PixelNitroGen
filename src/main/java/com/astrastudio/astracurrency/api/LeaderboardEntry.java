package com.astrastudio.astracurrency.api;

import java.math.BigDecimal;
import java.util.UUID;

public record LeaderboardEntry(int rank, UUID playerId, String lastKnownName, BigDecimal balance) {}
