package dev.queue;

import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class QueueListener implements Listener {

    private final QueueManager manager;
    private final Map<UUID, Long> cooldown = new HashMap<>();

    public QueueListener(QueueManager manager) {
        this.manager = manager;
    }

    @EventHandler
    public void onInteract(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return; // ignore the off-hand duplicate event

        String queueName = manager.linkedQueue(e.getRightClicked().getUniqueId());
        if (queueName == null) return;

        e.setCancelled(true);
        Player p = e.getPlayer();

        long now = System.currentTimeMillis();
        Long last = cooldown.get(p.getUniqueId());
        if (last != null && now - last < 500) return;
        cooldown.put(p.getUniqueId(), now);

        Queue q = manager.get(queueName);
        if (q == null) return;

        if (!p.hasPermission("queue.use")) {
            p.sendMessage(QueueManager.msg("You don't have permission to use queues.", NamedTextColor.RED));
            return;
        }

        if (q.name.equals(manager.queueOf(p.getUniqueId()))) {
            manager.leave(p);
        } else {
            manager.join(p, q);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        manager.leave(e.getPlayer().getUniqueId());
        cooldown.remove(e.getPlayer().getUniqueId());
    }
}
