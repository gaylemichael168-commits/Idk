package dev.queue;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public class QueuePlugin extends JavaPlugin {

    private QueueManager manager;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        manager = new QueueManager(this);
        manager.load();

        // The "BungeeCord" channel is understood by Velocity too
        // (keep bungee-plugin-message-channel = true in velocity.toml, which is the default).
        getServer().getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");
        getServer().getMessenger().registerIncomingPluginChannel(this, "BungeeCord", manager);

        getServer().getPluginManager().registerEvents(new QueueListener(manager), this);

        PluginCommand cmd = getCommand("queue");
        if (cmd != null) {
            QueueCommand handler = new QueueCommand(manager);
            cmd.setExecutor(handler);
            cmd.setTabCompleter(handler);
        }

        manager.startTasks();
        getLogger().info("NpcQueue enabled with " + manager.all().size() + " queue(s).");
    }

    @Override
    public void onDisable() {
        if (manager != null) {
            manager.stopTasks();
        }
        getServer().getMessenger().unregisterOutgoingPluginChannel(this);
        getServer().getMessenger().unregisterIncomingPluginChannel(this);
    }
}
