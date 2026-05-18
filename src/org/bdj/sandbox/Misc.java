package org.bdj.sandbox;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

public class Misc {

    private static boolean canConnect(String host, int port, int timeoutMs) {
        Socket socket = null;
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException e) {}
            }
        }
    }

    public static boolean isJailbroken() {
        return canConnect("127.0.0.1", 9021, 500);
    }

}