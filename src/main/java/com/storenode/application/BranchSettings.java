package com.storenode.application;

import java.time.ZoneId;
import java.util.UUID;

public record BranchSettings(UUID id, String code, ZoneId zoneId) {
}

