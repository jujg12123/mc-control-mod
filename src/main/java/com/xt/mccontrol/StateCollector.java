package com.xt.mccontrol;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.registry.RegistryKey;
import net.minecraft.world.World;

public class StateCollector {

    public static String collect(MinecraftClient client) {
        JsonObject state = new JsonObject();
        PlayerEntity player = client.player;
        if (player == null) return "{}";

        // 坐标、朝向
        state.addProperty("x", round(player.getX()));
        state.addProperty("y", round(player.getY()));
        state.addProperty("z", round(player.getZ()));
        state.addProperty("yaw", round(player.getYaw()));
        state.addProperty("pitch", round(player.getPitch()));

        // 生命值
        state.addProperty("health", round(player.getHealth()));

        // 维度
        RegistryKey<World> dimKey = player.getWorld().getRegistryKey();
        state.addProperty("dimension", dimKey.getValue().toString());

        // 视线目标（5 格内）
        HitResult hit = player.raycast(5.0, 0, false);
        if (hit.getType() == HitResult.Type.BLOCK) {
            BlockHitResult blockHit = (BlockHitResult) hit;
            BlockPos pos = blockHit.getBlockPos();
            BlockState blockState = player.getWorld().getBlockState(pos);
            state.addProperty("looking_at_block", blockState.getBlock().getName().getString());
            state.addProperty("looking_at_pos", pos.toShortString());
        } else {
            state.addProperty("looking_at_block", "none");
            state.addProperty("looking_at_pos", "");
        }

        // 周围实体（10 格内）
        JsonArray entities = new JsonArray();
        player.getWorld().getEntities().forEach(entity -> {
            double dist = entity.distanceTo(player);
            if (dist < 10 && entity != player) {
                JsonObject e = new JsonObject();
                e.addProperty("name", entity.getName().getString());
                e.addProperty("type", entity.getType().getName().getString());
                e.addProperty("distance", round(dist));
                entities.add(e);
            }
        });
        state.add("nearby_entities", entities);

        // 背包（0-35）
        JsonArray inventory = new JsonArray();
        PlayerInventory inv = player.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getStack(i);
            JsonObject item = new JsonObject();
            item.addProperty("slot", i);
            if (stack.isEmpty()) {
                item.addProperty("name", "empty");
                item.addProperty("count", 0);
                item.addProperty("durability", -1);
            } else {
                item.addProperty("name", stack.getItem().getName().getString());
                item.addProperty("count", stack.getCount());
                item.addProperty("durability", stack.getMaxDamage() - stack.getDamage());
            }
            inventory.add(item);
        }
        state.add("inventory", inventory);

        // 当前选中槽位
        state.addProperty("selected_slot", inv.selectedSlot);

        return state.toString();
    }

    private static double round(double val) {
        return Math.round(val * 10.0) / 10.0;
    }
}