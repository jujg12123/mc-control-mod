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
        server = new ControlServer();
        server.start();
        System.out.println("[MC-Control] WebSocket 服务已启动: ws://localhost:8765");

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) return;
            long now = System.currentTimeMillis();
            if (now - lastSendTime > SEND_INTERVAL_MS) {
                String stateJson = StateCollector.collect(client);
                if (server != null && stateJson != null) {
                    server.sendState(stateJson);
                }
                lastSendTime = now;
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            AutoBehaviorManager.tick(client);
        });
    }
}
