package com.xt.mccontrol;

import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.recipe.*;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.util.Identifier;
import net.minecraft.util.collection.DefaultedList;

import java.util.*;

/**
 * 动态配方查询器。
 * <p>
 * 使用 Minecraft 内置的 {@link RecipeManager} 查询任意物品的合成配方，
 * 支持有序合成、无序合成、熔炼、高炉、烟熏、切石机等配方类型。
 * <p>
 * 替代硬编码的配方表，能自动适应任何已注册的配方（包括模组配方），
 * 类似 JEI 物品管理器的配方查询机制。
 */
public class RecipeLookup {

    // ======================== 数据结构 ========================

    /** 单个配方信息 */
    public static class RecipeInfo {
        public String type;            // crafting_shaped, crafting_shapeless, smelting, blasting, smoking, stonecutting
        public String station;         // crafting_table, inventory, furnace, blast_furnace, smoker, stonecutter
        public String outputItemId;    // 如 "minecraft:oak_planks"
        public String outputName;      // 如 "橡木木板"
        public int outputCount;
        public List<IngredientSlot> ingredients = new ArrayList<>();
        public String patternText;     // 摆放方式文本（仅有序合成）
        public int gridWidth;
        public int gridHeight;

        /** 转为 AI 可读的文本 */
        public String toText() {
            StringBuilder sb = new StringBuilder();
            String typeLabel = getTypeLabel(type);
            String stationLabel = getStationLabel(station);
            sb.append("配方类型: ").append(typeLabel);
            sb.append("\n合成站: ").append(stationLabel);
            sb.append("\n产物: ").append(outputName).append(" ×").append(outputCount);
            sb.append(" (").append(outputItemId).append(")");

            // 材料列表
            sb.append("\n材料:");
            for (IngredientSlot ing : ingredients) {
                sb.append("\n  - ").append(ing.itemName);
                if (ing.count > 1) sb.append(" ×").append(ing.count);
                // 显示替代物品
                if (ing.allMatchIds.size() > 1) {
                    sb.append(" (可替代: ");
                    int shown = 0;
                    for (String alt : ing.allMatchIds) {
                        if (alt.equals(ing.itemId)) continue;
                        if (shown >= 3) {
                            sb.append("等");
                            break;
                        }
                        if (shown > 0) sb.append(", ");
                        sb.append(alt);
                        shown++;
                    }
                    sb.append(")");
                }
            }

            // 摆放方式（有序合成）
            if (patternText != null && !patternText.isEmpty()) {
                sb.append("\n摆放方式:\n").append(patternText);
            }

            return sb.toString();
        }
    }

    /** 配方中的单个材料槽位 */
    public static class IngredientSlot {
        public String itemId;              // 第一个匹配的物品 ID（如 "oak_planks"）
        public String itemName;            // 物品名称（如 "橡木木板"）
        public List<String> allMatchIds;   // 所有匹配的物品 ID（支持标签，如各种木板）
        public int count;                  // 需要数量
        public int slot;                   // 槽位索引（0-8 有序合成，-1 无序/熔炼）
    }

    // ======================== 核心查询方法 ========================

    /**
     * 查询指定物品的所有合成配方。
     *
     * @param itemId 物品 ID，如 "oak_planks" 或 "minecraft:oak_planks"
     * @return 所有匹配的配方列表，可能为空
     */
    public static List<RecipeInfo> findRecipes(String itemId) {
        List<RecipeInfo> results = new ArrayList<>();
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) {
            return results;
        }

        RecipeManager rm = client.world.getRecipeManager();
        RegistryWrapper.WrapperLookup registryAccess = client.world.getRegistryManager();

        // 解析目标物品
        Identifier targetId = parseIdentifier(itemId);
        Item targetItem = Registries.ITEM.get(targetId);
        if (targetItem == Items.AIR) {
            return results;
        }

        // 搜索合成台配方
        findCraftingRecipes(rm, targetItem, registryAccess, results);

        // 搜索熔炉配方
        findCookingRecipes(rm, RecipeType.SMELTING, "smelting", "furnace",
                targetItem, registryAccess, results);

        // 搜索高炉配方
        findCookingRecipes(rm, RecipeType.BLASTING, "blasting", "blast_furnace",
                targetItem, registryAccess, results);

        // 搜索烟熏炉配方
        findCookingRecipes(rm, RecipeType.SMOKING, "smoking", "smoker",
                targetItem, registryAccess, results);

        // 搜索切石机配方
        findStonecuttingRecipes(rm, targetItem, registryAccess, results);

        return results;
    }

    /**
     * 从配方列表中选取最适合自动合成的配方。
     * 优先级：有序合成 > 无序合成 > 熔炼 > 其他
     */
    public static RecipeInfo pickBestRecipe(List<RecipeInfo> recipes) {
        if (recipes == null || recipes.isEmpty()) return null;

        // 优先有序合成
        for (RecipeInfo r : recipes) {
            if ("crafting_shaped".equals(r.type)) return r;
        }
        // 其次无序合成
        for (RecipeInfo r : recipes) {
            if ("crafting_shapeless".equals(r.type)) return r;
        }
        // 最后熔炼
        for (RecipeInfo r : recipes) {
            if ("smelting".equals(r.type)) return r;
        }
        // 其他
        return recipes.get(0);
    }

    /**
     * 获取配方所需的所有材料（合并相同材料的数量）。
     * 返回 Map<物品ID(短名), 数量>
     */
    public static Map<String, Integer> getRequiredItems(RecipeInfo info) {
        Map<String, Integer> required = new LinkedHashMap<>();
        for (IngredientSlot ing : info.ingredients) {
            String key = stripNamespace(ing.itemId);
            required.merge(key, ing.count, Integer::sum);
        }
        return required;
    }

    /**
     * 获取配方所需的所有材料（含标签匹配信息）。
     * 返回每个材料的所有可接受物品 ID 列表和数量。
     */
    public static List<RequiredIngredient> getRequiredIngredients(RecipeInfo info) {
        List<RequiredIngredient> list = new ArrayList<>();
        for (IngredientSlot ing : info.ingredients) {
            RequiredIngredient req = new RequiredIngredient();
            req.matchIds = new HashSet<>();
            for (String id : ing.allMatchIds) {
                req.matchIds.add(stripNamespace(id));
            }
            req.count = ing.count;
            req.displayName = ing.itemName;
            list.add(req);
        }
        // 合并相同匹配集的材料
        Map<String, RequiredIngredient> merged = new LinkedHashMap<>();
        for (RequiredIngredient req : list) {
            String key = req.matchIds.toString();
            RequiredIngredient existing = merged.get(key);
            if (existing != null) {
                existing.count += req.count;
            } else {
                merged.put(key, req);
            }
        }
        return new ArrayList<>(merged.values());
    }

    /** 合并后的所需材料 */
    public static class RequiredIngredient {
        public Set<String> matchIds;  // 所有可接受的物品 ID（短名）
        public int count;
        public String displayName;

        /** 检查指定物品 ID 是否匹配此材料 */
        public boolean matches(String itemId) {
            return matchIds.contains(itemId);
        }
    }

    // ======================== 内部查询方法 ========================

    @SuppressWarnings("unchecked")
    private static void findCraftingRecipes(RecipeManager rm, Item targetItem,
            RegistryWrapper.WrapperLookup registryAccess, List<RecipeInfo> results) {
        Collection<RecipeEntry<CraftingRecipe>> recipes = rm.listAllOfType(RecipeType.CRAFTING);
        for (RecipeEntry<CraftingRecipe> entry : recipes) {
            CraftingRecipe recipe = entry.value();
            ItemStack output;
            try {
                output = recipe.getResult(registryAccess);
            } catch (Exception e) {
                continue;
            }
            if (output.getItem() != targetItem) continue;

            RecipeInfo info = new RecipeInfo();
            info.outputItemId = getItemId(output.getItem());
            info.outputName = output.getName().getString();
            info.outputCount = output.getCount();

            DefaultedList<Ingredient> ings = recipe.getIngredients();

            if (recipe instanceof ShapedRecipe shaped) {
                info.type = "crafting_shaped";
                info.gridWidth = shaped.getWidth();
                info.gridHeight = shaped.getHeight();
                info.station = (info.gridWidth <= 2 && info.gridHeight <= 2)
                        ? "inventory" : "crafting_table";
                info.patternText = buildShapedPattern(ings, info.gridWidth, info.gridHeight, info.ingredients);
            } else if (recipe instanceof ShapelessRecipe) {
                info.type = "crafting_shapeless";
                info.station = "crafting_table";
                buildShapelessIngredients(ings, info.ingredients);
            } else {
                // 其他特殊合成配方
                info.type = "crafting_special";
                info.station = "crafting_table";
                buildShapelessIngredients(ings, info.ingredients);
            }

            results.add(info);
        }
    }

    private static <T extends Recipe<?>> void findCookingRecipes(RecipeManager rm,
            RecipeType<T> recipeType, String typeName, String station,
            Item targetItem, RegistryWrapper.WrapperLookup registryAccess,
            List<RecipeInfo> results) {
        Collection<RecipeEntry<T>> recipes = rm.listAllOfType(recipeType);
        for (RecipeEntry<T> entry : recipes) {
            T recipe = entry.value();
            ItemStack output;
            try {
                output = recipe.getResult(registryAccess);
            } catch (Exception e) {
                continue;
            }
            if (output.getItem() != targetItem) continue;

            RecipeInfo info = new RecipeInfo();
            info.type = typeName;
            info.station = station;
            info.outputItemId = getItemId(output.getItem());
            info.outputName = output.getName().getString();
            info.outputCount = output.getCount();

            DefaultedList<Ingredient> ings = recipe.getIngredients();
            buildShapelessIngredients(ings, info.ingredients);

            results.add(info);
        }
    }

    private static void findStonecuttingRecipes(RecipeManager rm, Item targetItem,
            RegistryWrapper.WrapperLookup registryAccess, List<RecipeInfo> results) {
        Collection<RecipeEntry<StonecuttingRecipe>> recipes =
                rm.listAllOfType(RecipeType.STONECUTTING);
        for (RecipeEntry<StonecuttingRecipe> entry : recipes) {
            StonecuttingRecipe recipe = entry.value();
            ItemStack output;
            try {
                output = recipe.getResult(registryAccess);
            } catch (Exception e) {
                continue;
            }
            if (output.getItem() != targetItem) continue;

            RecipeInfo info = new RecipeInfo();
            info.type = "stonecutting";
            info.station = "stonecutter";
            info.outputItemId = getItemId(output.getItem());
            info.outputName = output.getName().getString();
            info.outputCount = output.getCount();

            DefaultedList<Ingredient> ings = recipe.getIngredients();
            buildShapelessIngredients(ings, info.ingredients);

            results.add(info);
        }
    }

    // ======================== 配方构建辅助 ========================

    /**
     * 构建有序合成的摆放方式和材料列表。
     */
    private static String buildShapedPattern(DefaultedList<Ingredient> ings,
            int width, int height, List<IngredientSlot> ingredients) {
        StringBuilder pattern = new StringBuilder();
        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                int idx = row * width + col;
                if (idx < ings.size()) {
                    Ingredient ing = ings.get(idx);
                    if (ing.isEmpty()) {
                        pattern.append("  .  ");
                    } else {
                        IngredientSlot slot = createIngredientSlot(ing, idx);
                        ingredients.add(slot);
                        String label = slot.itemName;
                        // 截短显示
                        if (label.length() > 4) label = label.substring(0, 4);
                        pattern.append(String.format("%-5s", label));
                    }
                } else {
                    pattern.append("  .  ");
                }
            }
            pattern.append("\n");
        }
        return pattern.toString().trim();
    }

    /**
     * 构建无序合成的材料列表。
     */
    private static void buildShapelessIngredients(DefaultedList<Ingredient> ings,
            List<IngredientSlot> ingredients) {
        for (int i = 0; i < ings.size(); i++) {
            Ingredient ing = ings.get(i);
            if (ing.isEmpty()) continue;
            IngredientSlot slot = createIngredientSlot(ing, -1);
            ingredients.add(slot);
        }
    }

    /**
     * 从 Ingredient 创建 IngredientSlot，提取所有匹配的物品信息。
     */
    private static IngredientSlot createIngredientSlot(Ingredient ing, int slotIdx) {
        IngredientSlot slot = new IngredientSlot();
        slot.slot = slotIdx;
        slot.count = 1;
        slot.allMatchIds = new ArrayList<>();

        ItemStack[] stacks;
        try {
            stacks = ing.getMatchingStacks();
        } catch (Exception e) {
            stacks = new ItemStack[0];
        }

        if (stacks.length > 0) {
            slot.itemId = stripNamespace(getItemId(stacks[0].getItem()));
            slot.itemName = stacks[0].getName().getString();
            for (ItemStack s : stacks) {
                String id = stripNamespace(getItemId(s.getItem()));
                if (!slot.allMatchIds.contains(id)) {
                    slot.allMatchIds.add(id);
                }
            }
        } else {
            slot.itemId = "unknown";
            slot.itemName = "未知材料";
            slot.allMatchIds.add("unknown");
        }

        return slot;
    }

    // ======================== 工具方法 ========================

    private static Identifier parseIdentifier(String id) {
        if (id.contains(":")) {
            return new Identifier(id);
        }
        return new Identifier("minecraft", id);
    }

    private static String getItemId(Item item) {
        Identifier id = Registries.ITEM.getId(item);
        return id != null ? id.toString() : "minecraft:air";
    }

    private static String stripNamespace(String fullId) {
        int idx = fullIDIndexOfColon(fullId);
        return idx >= 0 ? fullId.substring(idx + 1) : fullId;
    }

    private static int fullIDIndexOfColon(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == ':') return i;
        }
        return -1;
    }

    private static String getTypeLabel(String type) {
        return switch (type) {
            case "crafting_shaped" -> "有序合成";
            case "crafting_shapeless" -> "无序合成";
            case "crafting_special" -> "特殊合成";
            case "smelting" -> "熔炉烧炼";
            case "blasting" -> "高炉烧炼";
            case "smoking" -> "烟熏炉烹饪";
            case "stonecutting" -> "切石机";
            default -> type;
        };
    }

    private static String getStationLabel(String station) {
        return switch (station) {
            case "crafting_table" -> "工作台 (3×3)";
            case "inventory" -> "背包合成 (2×2)";
            case "furnace" -> "熔炉";
            case "blast_furnace" -> "高炉";
            case "smoker" -> "烟熏炉";
            case "stonecutter" -> "切石机";
            default -> station;
        };
    }

    /**
     * 将配方列表格式化为 AI 友好的完整文本。
     */
    public static String formatRecipeResults(String queryItem, List<RecipeInfo> recipes) {
        if (recipes.isEmpty()) {
            return "未找到 \"" + queryItem + "\" 的合成配方。\n"
                    + "该物品可能需要通过其他方式获得（挖矿、打怪、交易等），\n"
                    + "或者物品 ID 不正确。请检查物品 ID 后重试。";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("=== 配方查询: ").append(queryItem).append(" ===\n");
        sb.append("找到 ").append(recipes.size()).append(" 个配方:\n");

        for (int i = 0; i < recipes.size(); i++) {
            sb.append("\n--- 配方 #").append(i + 1).append(" ---\n");
            sb.append(recipes.get(i).toText());
        }

        return sb.toString();
    }
}
