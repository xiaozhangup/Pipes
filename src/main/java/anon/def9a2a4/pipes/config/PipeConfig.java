package anon.def9a2a4.pipes.config;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * Global configuration from config.yml.
 * Note: Per-variant transfer settings are now in the variants section
 * and are managed by PipeVariant and VariantRegistry.
 */
public class PipeConfig {
    private final boolean debugParticles;
    private final int particleInterval;

    // Sleep / hibernation settings
    private final long sourceEmptySleepMs;
    private final long destFullSleepMs;

    // Recipe unlock settings
    private final String unlockAdvancement;
    private final boolean showUnlockMessage;
    private final String unlockMessage;

    public PipeConfig(FileConfiguration config) {
        this.debugParticles = config.getBoolean("global.debug.particles", false);
        this.particleInterval = config.getInt("global.debug.particle-interval", 10);

        // Sleep durations: ticks → milliseconds
        int sourceEmptyTicks = config.getInt("global.performance.sleep.source-empty-ticks", 40);
        int destFullTicks = config.getInt("global.performance.sleep.dest-full-ticks", 40);
        this.sourceEmptySleepMs = sourceEmptyTicks * 50L;
        this.destFullSleepMs = destFullTicks * 50L;

        // Recipe unlock settings
        this.unlockAdvancement = config.getString("recipes.unlock-advancement", "minecraft:story/smelt_iron");
        this.showUnlockMessage = config.getBoolean("recipes.show-unlock-message", true);
        this.unlockMessage = config.getString("recipes.unlock-message", "<gold>You've unlocked pipe crafting recipes!");
    }

    public boolean isDebugParticles() {
        return debugParticles;
    }

    public int getParticleInterval() {
        return particleInterval;
    }

    public long getSourceEmptySleepMs() {
        return sourceEmptySleepMs;
    }

    public long getDestFullSleepMs() {
        return destFullSleepMs;
    }

    public String getUnlockAdvancement() {
        return unlockAdvancement;
    }

    public boolean isShowUnlockMessage() {
        return showUnlockMessage;
    }

    public String getUnlockMessage() {
        return unlockMessage;
    }

    public boolean isUnlockEnabled() {
        return unlockAdvancement != null && !unlockAdvancement.isEmpty()
               && !unlockAdvancement.equalsIgnoreCase("none");
    }
}
