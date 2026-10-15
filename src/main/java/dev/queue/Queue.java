package dev.queue;

import java.util.LinkedList;
import java.util.UUID;

public class Queue {
    public final String name;
    public String server;
    public int maxPlayers;
    public boolean open;
    public boolean paused;
    public final LinkedList<UUID> line = new LinkedList<>();

    public Queue(String name, String server, int maxPlayers, boolean open, boolean paused) {
        this.name = name;
        this.server = server;
        this.maxPlayers = maxPlayers;
        this.open = open;
        this.paused = paused;
    }
}
