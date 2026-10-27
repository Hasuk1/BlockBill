package dev.blockbill.domain;

/** Transient per-player state; it is safe to reset it after disconnect or respawn. */
public final class SprintTracker {
    public static final int CONTINUOUS_TICKS = 120;
    public static final int TTL_TICKS = 80;
    public static final int COOLDOWN_TICKS = 160;

    private int continuousTicks;
    private int cooldownTicks;

    /** Returns true only on a tick that should apply a short Slowness I effect. */
    public boolean tick(boolean survival, boolean sprinting, boolean hasAccess) {
        if (!survival || hasAccess) {
            reset();
            return false;
        }
        if (cooldownTicks > 0) {
            cooldownTicks--;
        }
        if (!sprinting) {
            continuousTicks = 0;
            return false;
        }
        continuousTicks = Math.min(CONTINUOUS_TICKS, continuousTicks + 1);
        if (continuousTicks == CONTINUOUS_TICKS && cooldownTicks == 0) {
            continuousTicks = 0;
            cooldownTicks = COOLDOWN_TICKS;
            return true;
        }
        return false;
    }

    public void reset() {
        continuousTicks = 0;
        cooldownTicks = 0;
    }

    public int continuousTicks() { return continuousTicks; }
    public int cooldownTicks() { return cooldownTicks; }
}
