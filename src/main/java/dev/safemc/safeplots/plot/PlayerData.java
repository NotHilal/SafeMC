package dev.safemc.safeplots.plot;

import org.jspecify.annotations.Nullable;

/** Per-player settings, keyed by UUID. The name is only a display cache and is refreshed on login. */
final class PlayerData {
    static final int DEFAULT_MAX_CLAIMS = 1;

    int maxClaims = DEFAULT_MAX_CLAIMS;
    @Nullable String name;
}
