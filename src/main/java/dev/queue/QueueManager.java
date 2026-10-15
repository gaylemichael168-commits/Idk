package dev.queue;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class QueueManager implements PluginMessageListener {

    private final QueuePlugin plugin;

    private final Map<String, Queue> queues = new LinkedHashMap<>();
    private final Map<UUID, String> membership = new HashMap<>();   // player -> queue name
    private final Map<UUID, String> npcLinks = new HashMap<>();     // npc entity uuid -> queue name

    private final Map<String, Integer> counts = new HashMap<>();    // server -> last known player count
    private final Map<String, Long> countTimes = new HashMap<>();   // server -> when we learned it

    private final File dataFile;
    private int intervalSeconds = 5;
    private BukkitTask sendTask;
    private BukkitTask barTask;

    public QueueManager(QueuePlugin plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "data.yml");
    }

    // ---------- helpers ----------

    public static Component msg(String text, NamedTextColor color) {
        return Component.text("[Queue] ", NamedTextColor.GRAY).append(Component.text(text, color));
    }

    public Collection<Queue> all() {
        return queues.values();
    }

    public Queue get(String name) {
        return name == null ? null : queues.get(name.toLowerCase());
    }

    public String queueOf(UUID player) {
        return membership.get(player);
    }

    public int position(UUID player) {
        String qn = membership.get(player);
        if (qn == null) return -1;
        Queue q = queues.get(qn);
        if (q == null) return -1;
        int i = 0;
        for (UUID id : q.line) {
            i++;
            if (id.equals(player)) return i;
        }
        return -1;
    }

    // ---------- config / data ----------

    public void load() {
        plugin.reloadConfig();
        FileConfiguration cfg = plugin.getConfig();
        intervalSeconds = Math.max(1, cfg.getInt("settings.check-interval-seconds", 5));

        Map<String, Queue> fresh = new LinkedHashMap<>();
        ConfigurationSection sec = cfg.getConfigurationSection("queues");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                ConfigurationSection q = sec.getConfigurationSection(key);
                if (q == null) continue;
                Queue nq = new Queue(
                        key.toLowerCase(),
                        q.getString("server", "lobby"),
                        q.getInt("max-players", 50),
                        q.getBoolean("open", true),
                        q.getBoolean("paused", false));
                Queue old = queues.get(nq.name);
                if (old != null) nq.line.addAll(old.line); // keep people's places on reload
                fresh.put(nq.name, nq);
            }
        }
        queues.clear();
        queues.putAll(fresh);
        membership.entrySet().removeIf(e -> !queues.containsKey(e.getValue()));
        loadLinks();
    }

    public void saveQueues() {
        FileConfiguration cfg = plugin.getConfig();
        cfg.set("queues", null);
        for (Queue q : queues.values()) {
            String p = "queues." + q.name + ".";
            cfg.set(p + "server", q.server);
            cfg.set(p + "max-players", q.maxPlayers);
            cfg.set(p + "open", q.open);
            cfg.set(p + "paused", q.paused);
        }
        plugin.saveConfig();
    }

    private void loadLinks() {
        npcLinks.clear();
        if (!dataFile.exists()) return;
        YamlConfiguration data = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection sec = data.getConfigurationSection("links");
        if (sec == null) return;
        for (String key : sec.getKeys(false)) {
            try {
                String qn = sec.getString(key);
                if (qn != null && queues.containsKey(qn.toLowerCase())) {
                    npcLinks.put(UUID.fromString(key), qn.toLowerCase());
                }
            } catch (IllegalArgumentException ignored) {
                // bad uuid in data.yml, skip it
            }
        }
    }

    private void saveLinks() {
        YamlConfiguration data = new YamlConfiguration();
        for (Map.Entry<UUID, String> e : npcLinks.entrySet()) {
            data.set("links." + e.getKey(), e.getValue());
        }
        try {
            plugin.getDataFolder().mkdirs();
            data.save(dataFile);
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not save data.yml: " + ex.getMessage());
        }
    }

    // ---------- NPC links ----------

    public String linkedQueue(UUID entity) {
        return npcLinks.get(entity);
    }

    public void link(UUID entity, Queue q) {
        npcLinks.put(entity, q.name);
        saveLinks();
    }

    public boolean unlink(UUID entity) {
        boolean removed = npcLinks.remove(entity) != null;
        if (removed) saveLinks();
        return removed;
    }

    // ---------- joining / leaving ----------

    public void join(Player p, Queue q) {
        String current = membership.get(p.getUniqueId());
        if (current != null) {
            if (current.equals(q.name)) {
                p.sendMessage(msg("You are already in this queue (position " + position(p.getUniqueId()) + ").", NamedTextColor.YELLOW));
            } else {
                p.sendMessage(msg("You are already in queue '" + current + "'. Use /queue leave first.", NamedTextColor.RED));
            }
            return;
        }
        if (!q.open) {
            p.sendMessage(msg("The queue '" + q.name + "' is closed.", NamedTextColor.RED));
            return;
        }
        q.line.addLast(p.getUniqueId());
        membership.put(p.getUniqueId(), q.name);
        p.sendMessage(msg("You joined queue '" + q.name + "'. Position: " + q.line.size() + ".", NamedTextColor.GREEN));
    }

    public boolean leave(UUID id) {
        String qn = membership.remove(id);
        if (qn == null) return false;
        Queue q = queues.get(qn);
        if (q != null) q.line.remove(id);
        return true;
    }

    public void leave(Player p) {
        if (leave(p.getUniqueId())) {
            p.sendMessage(msg("You left the queue.", NamedTextColor.YELLOW));
        } else {
            p.sendMessage(msg("You are not in a queue.", NamedTextColor.RED));
        }
    }

    // ---------- tasks ----------

    public void startTasks() {
        stopTasks();
        long period = intervalSeconds * 20L;
        sendTask = Bukkit.getScheduler().runTaskTimer(plugin, this::processQueues, period, period);
        barTask = Bukkit.getScheduler().runTaskTimer(plugin, this::updateActionBars, 20L, 20L);
    }

    public void stopTasks() {
        if (sendTask != null) sendTask.cancel();
        if (barTask != null) barTask.cancel();
        sendTask = null;
        barTask = null;
    }

    private void processQueues() {
        long now = System.currentTimeMillis();
        long maxAge = intervalSeconds * 3000L;

        for (Queue q : queues.values()) {
            // 1) send people using the last count we received
            if (!q.paused && !q.line.isEmpty()) {
                Integer count = counts.get(q.server);
                Long when = countTimes.get(q.server);
                if (count != null && when != null && now - when <= maxAge) {
                    int space = q.maxPlayers - count;
                    while (space > 0 && !q.line.isEmpty()) {
                        UUID id = q.line.pollFirst();
                        membership.remove(id);
                        Player p = Bukkit.getPlayer(id);
                        if (p == null) continue;
                        p.sendMessage(msg("It's your turn! Sending you to " + q.server + "...", NamedTextColor.GREEN));
                        sendToServer(p, q.server);
                        space--;
                        count++;
                        counts.put(q.server, count); // so other queues don't overfill it this cycle
                    }
                }
            }
            // 2) ask the proxy for a fresh count for next time
            requestCount(q.server);
        }
    }

    private void updateActionBars() {
        for (Queue q : queues.values()) {
            int pos = 0;
            int total = q.line.size();
            for (UUID id : q.line) {
                pos++;
                Player p = Bukkit.getPlayer(id);
                if (p == null) continue;
                String text = "Queue " + q.name + ": position " + pos + " of " + total
                        + (q.paused ? " (paused)" : "");
                p.sendActionBar(Component.text(text, q.paused ? NamedTextColor.YELLOW : NamedTextColor.AQUA));
            }
        }
    }

    // ---------- proxy messaging ----------

    private void requestCount(String server) {
        Player any = Bukkit.getOnlinePlayers().stream().findFirst().orElse(null);
        if (any == null) return; // plugin messages need at least one player online
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("PlayerCount");
        out.writeUTF(server);
        any.sendPluginMessage(plugin, "BungeeCord", out.toByteArray());
    }

    public void sendToServer(Player p, String server) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("Connect");
        out.writeUTF(server);
        p.sendPluginMessage(plugin, "BungeeCord", out.toByteArray());
    }

    @Override
    public void onPluginMessageReceived(@NotNull String channel, @NotNull Player player, byte @NotNull [] message) {
        if (!channel.equals("BungeeCord")) return;
        ByteArrayDataInput in = ByteStreams.newDataInput(message);
        String sub = in.readUTF();
        if (sub.equals("PlayerCount")) {
            String server = in.readUTF();
            int count = in.readInt();
            counts.put(server, count);
            countTimes.put(server, System.currentTimeMillis());
        }
    }
}
