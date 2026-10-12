package anon.def9a2a4.pipes.config;

import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.HashMap;
import java.util.Map;

/**
 * Internal display configuration from display.yml.
 * Contains only texture sets for the existing physical skull blocks.
 */
public class DisplayConfig {
    // Texture sets keyed by ID
    private final Map<String, TextureSet> textureSets;

    public DisplayConfig(FileConfiguration config) {
        this.textureSets = loadTextureSets(config);
    }

    private Map<String, TextureSet> loadTextureSets(FileConfiguration config) {
        Map<String, TextureSet> sets = new HashMap<>();
        ConfigurationSection section = config.getConfigurationSection("texture-sets");
        if (section == null) {
            return sets;
        }

        for (String setId : section.getKeys(false)) {
            ConfigurationSection setSection = section.getConfigurationSection(setId);
            if (setSection == null) continue;

            String itemTexture = setSection.getString("item");
            if (itemTexture == null || itemTexture.isEmpty()) continue;

            // Parse head textures
            Map<BlockFace, String> headTextures = new HashMap<>();
            ConfigurationSection headSection = setSection.getConfigurationSection("head");
            if (headSection != null) {
                String horizontal = headSection.getString("horizontal");
                if (horizontal != null) headTextures.put(BlockFace.NORTH, horizontal);

                String up = headSection.getString("up");
                if (up != null) headTextures.put(BlockFace.UP, up);

                String down = headSection.getString("down");
                if (down != null) headTextures.put(BlockFace.DOWN, down);
            }
            if (headTextures.isEmpty()) {
                headTextures.put(BlockFace.NORTH, itemTexture);
            }

            sets.put(setId, new TextureSet(itemTexture, headTextures));
        }

        return sets;
    }

    /**
     * Get a texture set by ID.
     * @return The texture set, or null if not found
     */
    public TextureSet getTextureSet(String id) {
        return textureSets.get(id);
    }

    /**
     * Check if a texture set exists.
     */
    public boolean hasTextureSet(String id) {
        return textureSets.containsKey(id);
    }

    /** Textures for the existing physical skull blocks, not the model display entity. */
    public record TextureSet(String item, Map<BlockFace, String> head) {
        public String getHeadTexture(BlockFace facing) {
            if (facing == BlockFace.UP || facing == BlockFace.DOWN) {
                String texture = head.get(facing);
                if (texture != null) return texture;
            }
            return head.getOrDefault(BlockFace.NORTH, item);
        }
    }
}
