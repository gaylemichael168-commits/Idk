package dev.queue;

import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class QueueCommand implements CommandExecutor, TabCompleter {

    private static final List<String> PLAYER_SUBS = List.of("join", "leave", "position");
    private static final List<String> ADMIN_SUBS = List.of(
            "open", "close", "pause", "resume", "setserver", "reload", "linknpc", "unlinknpc");
    private static final List<String> NAME_SUBS = List.of(
            "join", "open", "close", "pause", "resume", "setserver", "linknpc");

    private final QueueManager m;

    public QueueCommand(QueueManager manager) {
        this.m = manager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, String[] args) {
        if (!sender.hasPermission("queue.use")) {
            sender.sendMessage(QueueManager.msg("You don't have permission.", NamedTextColor.RED));
            return true;
        }

        if (args.length == 0) {
            list(sender);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        if (ADMIN_SUBS.contains(sub) && !sender.hasPermission("queue.admin")) {
            sender.sendMessage(QueueManager.msg("You don't have permission to do that.", NamedTextColor.RED));
            return true;
        }

        switch (sub) {
            case "list" -> list(sender);

            case "join" -> {
                Player p = playerOnly(sender);
                if (p == null) return true;
                Queue q = needQueue(sender, args, 1, "/queue join <name>");
                if (q != null) m.join(p, q);
            }

            case "leave" -> {
                Player p = playerOnly(sender);
                if (p != null) m.leave(p);
            }

            case "position" -> {
                Player p = playerOnly(sender);
                if (p == null) return true;
                int pos = m.position(p.getUniqueId());
                if (pos < 0) {
                    p.sendMessage(QueueManager.msg("You are not in a queue.", NamedTextColor.RED));
                } else {
                    Queue q = m.get(m.queueOf(p.getUniqueId()));
                    p.sendMessage(QueueManager.msg("Queue '" + q.name + "': position " + pos
                            + " of " + q.line.size() + (q.paused ? " (paused)" : "") + ".", NamedTextColor.AQUA));
                }
            }

            case "open" -> {
                Queue q = needQueue(sender, args, 1, "/queue open <name>");
                if (q == null) return true;
                q.open = true;
                m.saveQueues();
                sender.sendMessage(QueueManager.msg("Queue '" + q.name + "' is now open.", NamedTextColor.GREEN));
            }

            case "close" -> {
                Queue q = needQueue(sender, args, 1, "/queue close <name>");
                if (q == null) return true;
                q.open = false;
                m.saveQueues();
                sender.sendMessage(QueueManager.msg("Queue '" + q.name
                        + "' is now closed. Nobody new can join; players already waiting stay in line and will still be sent.",
                        NamedTextColor.YELLOW));
            }

            case "pause" -> {
                Queue q = needQueue(sender, args, 1, "/queue pause <name>");
                if (q == null) return true;
                q.paused = true;
                m.saveQueues();
                sender.sendMessage(QueueManager.msg("Queue '" + q.name + "' is paused. Nobody will be sent until you resume it.",
                        NamedTextColor.YELLOW));
            }

            case "resume" -> {
                Queue q = needQueue(sender, args, 1, "/queue resume <name>");
                if (q == null) return true;
                q.paused = false;
                m.saveQueues();
                sender.sendMessage(QueueManager.msg("Queue '" + q.name + "' has resumed.", NamedTextColor.GREEN));
            }

            case "setserver" -> {
                Queue q = needQueue(sender, args, 1, "/queue setserver <name> <server>");
                if (q == null) return true;
                if (args.length < 3) {
                    sender.sendMessage(QueueManager.msg("Usage: /queue setserver <name> <server>", NamedTextColor.RED));
                    return true;
                }
                q.server = args[2];
                m.saveQueues();
                sender.sendMessage(QueueManager.msg("Queue '" + q.name + "' now sends players to '" + q.server
                        + "'. Make sure that name matches velocity.toml exactly.", NamedTextColor.GREEN));
            }

            case "reload" -> {
                m.load();
                m.startTasks();
                sender.sendMessage(QueueManager.msg("Configuration reloaded.", NamedTextColor.GREEN));
            }

            case "linknpc" -> {
                Player p = playerOnly(sender);
                if (p == null) return true;
                Queue q = needQueue(sender, args, 1, "/queue linknpc <name>");
                if (q == null) return true;
                Entity target = p.getTargetEntity(6);
                if (target == null || target instanceof Player && !target.hasMetadata("NPC")) {
                    p.sendMessage(QueueManager.msg("Look directly at the NPC (within 6 blocks) and try again.", NamedTextColor.RED));
                    return true;
                }
                m.link(target.getUniqueId(), q);
                p.sendMessage(QueueManager.msg("That NPC now opens queue '" + q.name + "'.", NamedTextColor.GREEN));
            }

            case "unlinknpc" -> {
                Player p = playerOnly(sender);
                if (p == null) return true;
                Entity target = p.getTargetEntity(6);
                if (target != null && m.unlink(target.getUniqueId())) {
                    p.sendMessage(QueueManager.msg("NPC unlinked.", NamedTextColor.GREEN));
                } else {
                    p.sendMessage(QueueManager.msg("Look directly at a linked NPC and try again.", NamedTextColor.RED));
                }
            }

            default -> sender.sendMessage(QueueManager.msg(
                    "Unknown subcommand. Try /queue, /queue leave or /queue position.", NamedTextColor.RED));
        }
        return true;
    }

    private void list(CommandSender sender) {
        if (m.all().isEmpty()) {
            sender.sendMessage(QueueManager.msg("There are no queues configured.", NamedTextColor.YELLOW));
            return;
        }
        sender.sendMessage(QueueManager.msg("Available queues:", NamedTextColor.AQUA));
        for (Queue q : m.all()) {
            String status = (q.open ? "open" : "closed") + (q.paused ? ", paused" : "");
            sender.sendMessage(QueueManager.msg(" - " + q.name + " [" + status + "] "
                    + q.line.size() + " waiting -> " + q.server, NamedTextColor.WHITE));
        }
    }

    private Player playerOnly(CommandSender sender) {
        if (sender instanceof Player p) return p;
        sender.sendMessage(QueueManager.msg("Only players can use that command.", NamedTextColor.RED));
        return null;
    }

    private Queue needQueue(CommandSender sender, String[] args, int index, String usage) {
        if (args.length <= index) {
            sender.sendMessage(QueueManager.msg("Usage: " + usage, NamedTextColor.RED));
            return null;
        }
        Queue q = m.get(args[index]);
        if (q == null) {
            sender.sendMessage(QueueManager.msg("No queue named '" + args[index] + "'.", NamedTextColor.RED));
        }
        return q;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            out.addAll(PLAYER_SUBS);
            if (sender.hasPermission("queue.admin")) out.addAll(ADMIN_SUBS);
        } else if (args.length == 2 && NAME_SUBS.contains(args[0].toLowerCase(Locale.ROOT))) {
            for (Queue q : m.all()) out.add(q.name);
        }
        String typed = args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(typed));
        return out;
    }
}
