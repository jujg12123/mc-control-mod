package com.xt.mccontrol;

import net.minecraft.client.MinecraftClient;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;

public class ControlServer extends WebSocketServer {
    // 支持多连接：插件重连或误开多个实例时，状态/结果广播给所有存活连接，
    // 避免只记录最后一个连接导致其他连接收不到状态（表现为"已连接但未收到状态"）
    private final java.util.Set<WebSocket> connections = new java.util.concurrent.CopyOnWriteArraySet<>();

    public ControlServer() {
        super(new InetSocketAddress("localhost", 8765));
        setReuseAddr(true);
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        connections.add(conn);
        System.out.println("[MC-Control] AI 大脑已连接 (当前连接数: " + connections.size() + ")");
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        connections.remove(conn);
        System.out.println("[MC-Control] AI 大脑已断开 (剩余连接数: " + connections.size() + ")");
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
        System.out.println("[MC-Control] WebSocket server started (ws://localhost:8765)");
    }

    public void sendState(String stateJson) {
        for (WebSocket conn : connections) {
            if (conn.isOpen()) {
                conn.send(stateJson);
            }
        }
    }

    /**
     * 发送动作执行结果给 AI 插件
     */
    public void sendActionResult(String resultJson) {
        for (WebSocket conn : connections) {
            if (conn.isOpen()) {
                conn.send(resultJson);
            }
        }
    }

    public int getConnectionCount() {
        return connections.size();
    }
}
