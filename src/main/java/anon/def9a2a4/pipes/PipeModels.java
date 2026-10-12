package anon.def9a2a4.pipes;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.bukkit.api.event.CraftEngineReloadEvent;
import org.bukkit.block.BlockFace;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Canonical Blockbench models and their rigid rotations, without connection stretching. */
public final class PipeModels implements Listener {
    private final Map<BlockFace, State> regular = new EnumMap<>(BlockFace.class);
    private final Map<Integer, State> corners = new HashMap<>();
    private final List<String> families = new ArrayList<>();
    private Map<String, ItemStack> displayItems = Map.of();
    private final PipesPlugin plugin;

    public PipeModels(PipesPlugin plugin) {
        this.plugin = plugin;
        try (var reader = new InputStreamReader(Objects.requireNonNull(plugin.getResource("pipe-models.json")), StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            Map<String, Quaternionf> rotations = new HashMap<>();
            root.getAsJsonObject("rotations").entrySet().forEach(entry -> {
                var q = entry.getValue().getAsJsonArray();
                rotations.put(entry.getKey(), new Quaternionf(q.get(0).getAsFloat(), q.get(1).getAsFloat(),
                        q.get(2).getAsFloat(), q.get(3).getAsFloat()));
            });
            root.getAsJsonArray("families").forEach(family -> families.add(family.getAsString()));
            root.getAsJsonObject("regular").entrySet().forEach(entry -> regular.put(
                    BlockFace.valueOf(entry.getKey().toUpperCase(Locale.ROOT)), state(entry.getValue().getAsJsonObject(), rotations)));
            root.getAsJsonObject("corner").entrySet().forEach(entry -> corners.put(
                    Integer.parseInt(entry.getKey()), state(entry.getValue().getAsJsonObject(), rotations)));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read pipe-models.json", e);
        }
    }

    @EventHandler
    public void onCraftEngineReload(CraftEngineReloadEvent event) {
        plugin.initializeModels();
    }

    private static State state(JsonObject json, Map<String, Quaternionf> rotations) {
        return new State(json.get("family").getAsString(), rotations.get(json.get("rotation").getAsString()));
    }

    public void reload(Collection<PipeVariant> variants) {
        Map<String, ItemStack> items = new HashMap<>();
        for (String material : variants.stream().map(PipeModels::material).distinct().toList()) {
            for (String family : families) {
                String id = material + "/" + family;
                items.put(id, buildItem("pipes:display/" + id));
            }
        }
        displayItems = Map.copyOf(items);
    }

    private static ItemStack buildItem(String id) {
        var definition = CraftEngineItems.byId(id);
        if (definition == null) {
            throw new IllegalStateException("CraftEngine item '" + id + "' is missing. Load resources/pipes first.");
        }
        return definition.buildBukkitItem();
    }

    public Render render(PipeVariant variant, BlockFace facing, int inputMask, int outputMask) {
        State state = variant.getBehaviorType() == BehaviorType.REGULAR
                ? regular.get(facing) : corners.get(inputMask | outputMask << 6);
        if (state == null) {
            throw new IllegalArgumentException("No pipe model for " + facing + ", inputs=" + inputMask + ", outputs=" + outputMask);
        }
        ItemStack item = displayItems.get(material(variant) + "/" + state.family());
        // ItemDisplay adds a local Y half-turn even with display context NONE.
        // Cancel it before applying the model-to-world direction rotation.
        return new Render(item, new Transformation(new Vector3f(), new Quaternionf(state.rotation()),
                new Vector3f(1.001f, 1.001f, 1.001f), new Quaternionf(0, 1, 0, 0)));
    }

    public static int bit(BlockFace face) {
        return switch (face) {
            case NORTH -> 1;
            case SOUTH -> 2;
            case EAST -> 4;
            case WEST -> 8;
            case UP -> 16;
            case DOWN -> 32;
            default -> throw new IllegalArgumentException("Not an axial face: " + face);
        };
    }

    private static String material(PipeVariant variant) {
        String material = variant.getTextureSetId().replace("_corner", "");
        return material.equals("waxed_copper") ? "copper" : material;
    }

    private record State(String family, Quaternionf rotation) {}
    public record Render(ItemStack item, Transformation transformation) {}
}
