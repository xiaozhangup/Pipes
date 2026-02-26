package anon.def9a2a4.pipes;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;

import java.util.WeakHashMap;

public class WorldManager implements Listener {

    private final PipesPlugin plugin;
    private final WeakHashMap<World, PipeManager> pipeManager;

    public WorldManager(PipesPlugin plugin, WeakHashMap<World, PipeManager> pipeManager) {
        this.plugin = plugin;
        this.pipeManager = pipeManager;

        // Scan all loaded worlds for existing pipes (handles server restart)
        for (World world : Bukkit.getWorlds()) {
            PipeManager manager = new PipeManager(plugin, world);
            pipeManager.put(world, manager);

            manager.scanForExistingPipes();
            manager.startTasks();
        }
    }

    @EventHandler
    public void on(WorldLoadEvent event) {
        World world = event.getWorld();
        if (!pipeManager.containsKey(world)) {
            PipeManager manager = new PipeManager(plugin, world);
            pipeManager.put(world, manager);

            manager.scanForExistingPipes();
            manager.startTasks();
        }
    }

    @EventHandler
    public void on(WorldUnloadEvent event) {
        World world = event.getWorld();
        PipeManager manager = pipeManager.remove(world);
        if (manager != null) {
            manager.shutdown();
        }
    }

}
