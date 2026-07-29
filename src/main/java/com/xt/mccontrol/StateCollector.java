package com.xt.mccontrol;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.registry.RegistryKey;
import net.minecraft.world.World;

import java.util.Comparator;
import java.util.List;

public class StateCollector {

    private static String lastStateHash = null;
    private static JsonArray behaviorLog = new JsonArray();

    /**
     * 供 AutoBehaviorManager 写入行为日志（线程安全）。
     * collect() 在客户端主线程调用时会取走并清空日志。
     */
    public static synchronized void addBehaviorLog(String entry) {
        JsonObject log = new JsonObject();
        log.addProperty("time", System.currentTimeMillis());
        log.addProperty("event", entry);
        behaviorLog.add(log);
        // 最多保留 10 条
        while (behaviorLog.size() > 10) {
            behaviorLog.remove(0);
        }
    }

    public static String collect(MinecraftClient client) {
        JsonObject state = new JsonObject();
        PlayerEntity player = client.player;
        if (player == null) return "{}";

        // 消息类型（放在最前面）
        state.addProperty("type", "state");

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
            Identifier blockId = Registries.BLOCK.getId(blockState.getBlock());
            state.addProperty("looking_at_block_id", blockId != null ? blockId.toString() : "unknown");
            state.addProperty("looking_at_pos", pos.toShortString());
        } else {
            state.addProperty("looking_at_block", "none");
            state.addProperty("looking_at_block_id", "none");
            state.addProperty("looking_at_pos", "");
        }

        // 脚下方块信息
        BlockPos belowPos = player.getBlockPos().down();
        BlockState belowBlock = player.getWorld().getBlockState(belowPos);
        String belowName = belowBlock.getBlock().getName().getString();
        Identifier belowId = Registries.BLOCK.getId(belowBlock.getBlock());
        state.addProperty("block_below", belowName);
        state.addProperty("block_below_id", belowId != null ? belowId.toString() : "unknown");
        state.addProperty("on_ground", player.isOnGround());
        state.addProperty("y_feet", round(player.getY()));

        // 饥饿值和饱和度
        state.addProperty("food_level", player.getHungerManager().getFoodLevel());
        state.addProperty("saturation", player.getHungerManager().getSaturationLevel());

        // 经验等级
        state.addProperty("experience_level", player.experienceLevel);

        // 游戏模式
        state.addProperty("game_mode", client.interactionManager.getCurrentGameMode().getName());

        // 生物群系（1.20.1: 通过 RegistryEntry 获取 biome 的 RegistryKey）
        String biome = player.getWorld().getBiome(player.getBlockPos())
                .getKey().map(RegistryKey::getValue).map(Identifier::toString).orElse("unknown");
        state.addProperty("biome", biome);

        // 游戏时间和天气
        state.addProperty("time", player.getWorld().getTimeOfDay());
        state.addProperty("is_day", player.getWorld().isDay());
        state.addProperty("weather", player.getWorld().isRaining()
                ? (player.getWorld().isThundering() ? "thunder" : "rain")
                : "clear");

        // 周围实体（16 格范围）
        JsonArray entities = new JsonArray();
        World world = player.getWorld();
        Box box = new Box(
                player.getX() - 16, player.getY() - 16, player.getZ() - 16,
                player.getX() + 16, player.getY() + 16, player.getZ() + 16);
        List<Entity> nearbyEntities = world.getOtherEntities(player, box, e -> e != player);
        // 按距离排序
        nearbyEntities.sort(Comparator.comparingDouble(e -> player.squaredDistanceTo(e)));
        // 最多 20 个实体
        int entityCount = 0;
        for (Entity entity : nearbyEntities) {
            if (entityCount >= 20) break;
            JsonObject ent = new JsonObject();
            Identifier entityId = Registries.ENTITY_TYPE.getId(entity.getType());
            ent.addProperty("type", entityId != null ? entityId.toString() : "unknown");
            ent.addProperty("name", entity.getName().getString());
            ent.addProperty("x", round(entity.getX()));
            ent.addProperty("y", round(entity.getY()));
            ent.addProperty("z", round(entity.getZ()));
            ent.addProperty("distance", round(Math.sqrt(player.squaredDistanceTo(entity))));
            entities.add(ent);
            entityCount++;
        }
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

        // behavior_log（线程安全：从主线程取走并清空）
        JsonArray logToInclude;
        synchronized (StateCollector.class) {
            if (behaviorLog.size() > 0) {
                logToInclude = behaviorLog;
                behaviorLog = new JsonArray(); // 清空，避免重复发送
            } else {
                logToInclude = null;
            }
        }
        if (logToInclude != null) {
            state.add("behavior_log", logToInclude);
        } else {
            state.add("behavior_log", new JsonArray());
        }

        // 状态去重：计算最终 JSON 的哈希，与上次相同则返回 null
        // 注意：排除 time 字段，因为它每 tick 都变化，会导致去重失效
        JsonObject hashCopy = state.deepCopy();
        hashCopy.remove("time");
        String hashStr = hashCopy.toString();
        int hash = hashStr.hashCode();
        if (lastStateHash != null && hash == Integer.parseInt(lastStateHash)) {
            return null; // 状态未变化
        }
        lastStateHash = String.valueOf(hash);
        return state.toString();
    }

    private static double round(double val) {
        return Math.round(val * 10.0) / 10.0;
    }
}
