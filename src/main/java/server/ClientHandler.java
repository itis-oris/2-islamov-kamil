package server;

import net.Protocol;
import java.io.*;
import java.net.*;

public class ClientHandler implements Runnable {
    private final Socket socket;
    private final GameServer server;
    public final int playerIndex;
    private BufferedReader in;
    private PrintWriter out;

    public ClientHandler(Socket socket, GameServer server, int playerIndex) {
        this.socket = socket;
        this.server = server;
        this.playerIndex = playerIndex;
    }

    public void send(String msg) {
        if (out != null) {
            out.print(msg);
            out.flush();
        }
    }

    @Override
    public void run() {
        try {
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()), true);
            send(Protocol.make("ASSIGN", String.valueOf(playerIndex)));
            String line;
            while ((line = in.readLine()) != null) {
                server.handleClientMessage(this, line);
            }
        } catch (IOException e) {
            System.err.println("Client disconnected: " + e.getMessage());
        } finally {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }
}
