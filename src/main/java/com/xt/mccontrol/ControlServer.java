package com.xt.mccontrol;

import net.minecraft.client.MinecraftClient;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;

public class ControlServer extends WebSocketServer {
    private WebSocket currentConnection = null;

    public ControlServer() {
        super(new InetSocketAddress("localhost", 8765));
        setReuseAddr(true);
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        currentConnection = conn;
        System.out.println("[MC-Control] AI 大脑已连接");
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        currentConnection = null;
        System.out.println("[MC-Control] AI 大脑已断开");
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        MinecraftClient client = MinecraftClient.getInstance();
        client.execute(() -> ActionExecutor.execute(message));
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        System.err.println("[MC-Control] WebSocket error: " + ex.getMessage());
    }

    @Override
    public void onStart() {
        System.out.println("[MC-Control] WebSocket server started");
    }

    public void sendState(String stateJson) {
        if (currentConnection != null && currentConnection.isOpen()) {
            currentConnection.send(stateJson);
        }
    }

    /**
     * 发送动作执行结果给 AI 插件
     */
    public void sendActionResult(String resultJson) {
        if (currentConnection != null && currentConnection.isOpen()) {
            currentConnection.send(resultJson);
        }
    }
}
