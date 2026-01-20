package net;

import java.io.*;
import java.net.*;
import java.util.function.Consumer;

/**
 * Лёгкий сетевой клиент-обёртка для GameClient.
 */
public class NetClient {
    private Socket socket;
    private BufferedReader in;
    private PrintWriter out;
    private Thread reader;

    public void connect(String host, int port, Consumer<String> onMessage) throws IOException {
        socket = new Socket(host, port);
        in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()), true);
        reader = new Thread(() -> {
            try {
                String line;
                while ((line = in.readLine()) != null) onMessage.accept(line);
            } catch (IOException e) { /*disconnected*/ }
        });
        reader.setDaemon(true);
        reader.start();
    }

    public void send(String msg) {
        if (out != null) {
            out.print(msg);
            out.flush();
        }
    }

    public void disconnect() {
        try { socket.close(); } catch (IOException ignored) {}
    }
}
