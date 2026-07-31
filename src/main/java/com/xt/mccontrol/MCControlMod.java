package com.xt.mccontrol;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;

public class MCControlMod implements ClientModInitializer {
    private static ControlServer server;
    private static long lastSendTime = 0;
    private static final long SEND_INTERVAL_MS = 200;

    public static ControlServer getServer() {
        return server;
    }

    @Override
    public void onInitializeClient() {
        // 预检端口占用：8765 被占用时（常见于开了多个 Minecraft 实例或其他程序），
        // 本 mod 可能无法接收连接，给出醒目提示便于排查
        try (java.net.ServerSocket probe = new java.net.ServerSocket(8765, 0,
                java.net.InetAddress.getByName("localhost"))) {
            // 端口空闲，正常启动
        } catch (Exception e) {
            System.err.println("[MC-Control] 警告: 端口 8765 已被其他进程占用！");
            System.err.println("[MC-Control] 请检查是否开了多个 Minecraft 实例，或用 netstat -ano | findstr 8765 查看占用进程。");
            System.err.println("[MC-Control] 端口被占用时 AI 可能连到错误的进程，导致'已连接但收不到游戏状态'。");
        }
        server = new ControlServer();
        server.start();
        System.out.println("[MC-Control] WebSocket 服务已启动: ws://localhost:8765");

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) return;

            // 推进长任务状态机（寻路/挖掘等），必须在主线程
            ActionExecutor.tick(client);

            // 自动行为（自卫/防饿/防卡/拾取）
            AutoBehaviorManager.tick(client);

            // 周期性状态上报
            long now = System.currentTimeMillis();
            if (now - lastSendTime > SEND_INTERVAL_MS) {
                String stateJson = StateCollector.collect(client);
                if (server != null && stateJson != null) {
                    server.sendState(stateJson);
                }
                lastSendTime = now;
            }
        });
    }
}
