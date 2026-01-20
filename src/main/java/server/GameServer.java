package server;

import model.*;
import net.Protocol;
import db.DatabaseManager;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Простой сервер: ждёт 2 клиента, держит authoritative GameState и шлёт UPDATE каждые 3 секунды.
 */
public class GameServer {
    public static final int PORT = 23456;
    private final ServerSocket serverSocket;
    private final List<ClientHandler> clients = Collections.synchronizedList(new ArrayList<>());
    private final GameState state = new GameState();
    private final ScheduledExecutorService ticks = Executors.newSingleThreadScheduledExecutor();
    private final DatabaseManager db;

    public GameServer() throws IOException {
        serverSocket = new ServerSocket(PORT);
        db = DatabaseManager.getInstance();
    }

    public void start() throws IOException {
        System.out.println("Server started on port " + PORT + ". Waiting for 2 clients...");
        while (clients.size() < 2) {
            Socket s = serverSocket.accept();
            ClientHandler h = new ClientHandler(s, this, clients.size());
            clients.add(h);

            // 🔹 Отправляем клиенту его индекс
            h.send(Protocol.make("ASSIGN", String.valueOf(clients.size() - 1)));

            new Thread(h).start();
            System.out.println("Client connected: " + s.getRemoteSocketAddress());
        }
        broadcast(Protocol.make("INFO","MATCH_START"));
        ticks.scheduleAtFixedRate(this::gameTick, 3000, 3000, TimeUnit.MILLISECONDS);
    }

    public void stop() throws IOException {
        ticks.shutdownNow();
        serverSocket.close();
    }

    public synchronized void broadcast(String msg) {
        for (ClientHandler c : clients) c.send(msg);
    }

    public synchronized void sendTo(int playerIndex, String msg) {
        if (playerIndex >= 0 && playerIndex < clients.size()) clients.get(playerIndex).send(msg);
    }

    public synchronized void handleClientMessage(ClientHandler from, String line) {
        Protocol.Parsed p = Protocol.parse(line);
        if (p == null) return;
        switch (p.type) {
            case "SELECT":
                state.setPlayerSelection(from.playerIndex, p.payload);
                broadcast(Protocol.make("SELECT", from.playerIndex + ":" + p.payload));
                break;
            case "READY":
                state.setReady(from.playerIndex, true);
                broadcast(Protocol.make("READY", String.valueOf(from.playerIndex)));
                if (state.bothReady()) {
                    state.startMatch();
                    broadcast(Protocol.make("START_GAME",""));
                }
                break;
            case "NOTREADY":
                state.setReady(from.playerIndex, false);
                broadcast(Protocol.make("NOTREADY", String.valueOf(from.playerIndex)));
                break;
            case "DEPLOY":
                state.deploy(from.playerIndex, p.payload);
                broadcast(Protocol.make("DEPLOY", from.playerIndex + ":" + p.payload));
                break;
            default:
                System.out.println("[Server] unknown message: " + line);
        }
    }

    private synchronized void gameTick() {
        if (!state.inMatch()) return;
        state.advanceTick();
        String payload = state.serializeForClients();
        broadcast(Protocol.make("UPDATE", payload));
        if (state.isMatchOver()) {
            String result = state.computeResult();
            broadcast(Protocol.make("END", result));
            db.saveResult(state);
            state.resetToMenu();
        }
    }

    public static void main(String[] args) throws Exception {
        GameServer s = new GameServer();
        s.start();
    }
}
