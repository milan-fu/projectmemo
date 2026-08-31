package com.sthstrange.projectmemo.client;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 客户端本地 .litematic 解析：方块统计 → 物品需求表（优先原版 block.asItem 映射，其余与服务端 LitematicImporter 一致；数量型方块按状态计数）。 */
public final class ClientLitematic {

    public static final class Parsed {
        public final LinkedHashMap<String, Long> counts = new LinkedHashMap<>(); // itemId -> 数量
        public long totalBlocks;
        public long skippedNonItem;
        public boolean hasOrigin;
        public int originX, originY, originZ;
        public String error;
    }

    public static Parsed parse(File file) {
        Parsed out = new Parsed();
        Map<String, Object> root;
        try {
            root = MemoNbt.readGzip(file);
        } catch (Exception e) {
            out.error = L10n.get("projectmemo.litematic.parseFail") + ": " + e.getMessage();
            return out;
        }
        Object regionsObj = root.get("Regions");
        if (!(regionsObj instanceof Map)) {
            out.error = L10n.get("projectmemo.litematic.noRegions");
            return out;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> regions = (Map<String, Object>) regionsObj;
        if (regions.isEmpty()) {
            out.error = L10n.get("projectmemo.litematic.empty");
            return out;
        }
        for (Map.Entry<String, Object> e : regions.entrySet()) {
            if (!(e.getValue() instanceof Map)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> region = (Map<String, Object>) e.getValue();
            try {
                parseRegion(region, out);
            } catch (Exception ex) {
                out.error = L10n.get("projectmemo.litematic.regionFail") + ": " + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
                return out;
            }
        }
        if (out.counts.isEmpty() && out.error == null) out.error = L10n.get("projectmemo.litematic.noItems");
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void parseRegion(Map<String, Object> region, Parsed out) {
        if (!out.hasOrigin) {
            int[] pos = xyz(region.get("Position"));
            if (pos != null) {
                out.hasOrigin = true;
                out.originX = pos[0];
                out.originY = pos[1];
                out.originZ = pos[2];
            }
        }
        Object paletteObj = region.get("BlockStatePalette");
        Object statesObj = region.get("BlockStates");
        Object sizeObj = region.get("Size");
        if (!(paletteObj instanceof List) || !(statesObj instanceof long[])) return;
        List<Object> palette = (List<Object>) paletteObj;
        long[] words = (long[]) statesObj;
        if (palette.isEmpty() || words.length == 0) return;

        int[] size = xyz(sizeObj);
        // 负 Size（Litematica 反向选区保存）取绝对值，否则体积算成负数 → 0 方块
        long volume = size == null ? 0
                : (long) Math.abs(size[0]) * Math.abs(size[1]) * Math.abs(size[2]);
        if (volume <= 0) return;
        if (volume > 64_000_000) { out.error = L10n.get("projectmemo.litematic.tooBig"); return; }

        int bits = Math.max(1, 32 - Integer.numberOfLeadingZeros(palette.size() - 1));
        long mask = (1L << bits) - 1;

        String[] names = new String[palette.size()];
        int[] mult = new int[palette.size()];
        for (int i = 0; i < palette.size(); i++) {
            mult[i] = 1;
            Object entry = palette.get(i);
            if (entry instanceof Map) {
                Map<?, ?> em = (Map<?, ?>) entry;
                Object name = em.get("Name");
                names[i] = name == null ? "" : name.toString();
                mult[i] = stateMultiplier(names[i], em.get("Properties"));
            } else names[i] = "";
        }

        for (long i = 0; i < volume; i++) {
            int bitIndex = (int) (i * bits);
            int wordIndex = bitIndex >> 6;
            int bitOffset = bitIndex & 63;
            if (wordIndex >= words.length) break;
            long value = (words[wordIndex] >>> bitOffset) & mask;
            if (bitOffset + bits > 64 && wordIndex + 1 < words.length) {
                long high = words[wordIndex + 1] & ((1L << (bitOffset + bits - 64)) - 1);
                value |= high << (64 - bitOffset);
            }
            int idx = (int) value;
            if (idx < 0 || idx >= names.length) continue;
            String name = names[idx];
            out.totalBlocks++;
            if (name.isEmpty() || name.endsWith(":air") || name.endsWith(":void_air") || name.endsWith(":cave_air"))
                continue;
            Item[] items = blockToItems(name);
            if (items == null) {
                out.skippedNonItem++;
                continue;
            }
            long qty = mult[idx];
            for (Item item : items) {
                String itemId = BuiltInRegistries.ITEM.getKey(item).toString();
                out.counts.merge(itemId, qty, Long::sum);
            }
        }
    }

    /** Size/Position 兼容两种旧格式：compound {x,y,z} 或 list [x,y,z] */
    private static int[] xyz(Object v) {
        if (v instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) v;
            Object x = m.get("x"), y = m.get("y"), z = m.get("z");
            if (x instanceof Number && y instanceof Number && z instanceof Number)
                return new int[]{((Number) x).intValue(), ((Number) y).intValue(), ((Number) z).intValue()};
            return null;
        }
        if (v instanceof List) {
            List<?> l = (List<?>) v;
            if (l.size() >= 3 && l.get(0) instanceof Number && l.get(1) instanceof Number && l.get(2) instanceof Number)
                return new int[]{((Number) l.get(0)).intValue(), ((Number) l.get(1)).intValue(), ((Number) l.get(2)).intValue()};
            return null;
        }
        return null;
    }

    /** 无同名物品方块的单件折算表（与服务端 LitematicImporter 一致，1.21.11 注册表核验，84 条） */
    private static final Map<String, String> BLOCK_ITEM_OVERRIDE = new java.util.HashMap<>();
    static {
        BLOCK_ITEM_OVERRIDE.put("minecraft:redstone_wire", "minecraft:redstone");
        BLOCK_ITEM_OVERRIDE.put("minecraft:carrots", "minecraft:carrot");
        BLOCK_ITEM_OVERRIDE.put("minecraft:potatoes", "minecraft:potato");
        BLOCK_ITEM_OVERRIDE.put("minecraft:beetroots", "minecraft:beetroot_seeds");
        BLOCK_ITEM_OVERRIDE.put("minecraft:melon_stem", "minecraft:melon_seeds");
        BLOCK_ITEM_OVERRIDE.put("minecraft:pumpkin_stem", "minecraft:pumpkin_seeds");
        BLOCK_ITEM_OVERRIDE.put("minecraft:sweet_berry_bush", "minecraft:sweet_berries");
        BLOCK_ITEM_OVERRIDE.put("minecraft:cocoa", "minecraft:cocoa_beans");
        BLOCK_ITEM_OVERRIDE.put("minecraft:kelp_plant", "minecraft:kelp");
        BLOCK_ITEM_OVERRIDE.put("minecraft:cave_vines", "minecraft:glow_berries");
        BLOCK_ITEM_OVERRIDE.put("minecraft:cave_vines_plant", "minecraft:glow_berries");
        BLOCK_ITEM_OVERRIDE.put("minecraft:wall_torch", "minecraft:torch");
        BLOCK_ITEM_OVERRIDE.put("minecraft:redstone_wall_torch", "minecraft:redstone_torch");
        BLOCK_ITEM_OVERRIDE.put("minecraft:soul_wall_torch", "minecraft:soul_torch");
        BLOCK_ITEM_OVERRIDE.put("minecraft:oak_wall_sign", "minecraft:oak_sign");
        BLOCK_ITEM_OVERRIDE.put("minecraft:spruce_wall_sign", "minecraft:spruce_sign");
        BLOCK_ITEM_OVERRIDE.put("minecraft:birch_wall_sign", "minecraft:birch_sign");
        BLOCK_ITEM_OVERRIDE.put("minecraft:acacia_wall_sign", "minecraft:acacia_sign");
        BLOCK_ITEM_OVERRIDE.put("minecraft:cherry_wall_sign", "minecraft:cherry_sign");
        BLOCK_ITEM_OVERRIDE.put("minecraft:jungle_wall_sign", "minecraft:jungle_sign");
        BLOCK_ITEM_OVERRIDE.put("minecraft:dark_oak_wall_sign", "minecraft:dark_oak_sign");
        BLOCK_ITEM_OVERRIDE.put("minecraft:pale_oak_wall_sign", "minecraft:pale_oak_sign");
        BLOCK_ITEM_OVERRIDE.put("minecraft:mangrove_wall_sign", "minecraft:mangrove_sign");
        BLOCK_ITEM_OVERRIDE.put("minecraft:bamboo_wall_sign", "minecraft:bamboo_sign");
        BLOCK_ITEM_OVERRIDE.put("minecraft:crimson_wall_sign", "minecraft:crimson_sign");
        BLOCK_ITEM_OVERRIDE.put("minecraft:warped_wall_sign", "minecraft:warped_sign");
        BLOCK_ITEM_OVERRIDE.put("minecraft:oak_wall_hanging_sign", "minecraft:oak_hanging_sign");
        BLOCK_ITEM_OVERRIDE.put("minecraft:spruce_wall_hanging_sign", "minecraft:spruce_hanging_sign");
        BLOCK_ITEM_OVERRIDE.put("minecraft:birch_wall_hanging_sign", "minecraft:birch_hanging_sign");
        BLOCK_ITEM_OVERRIDE.put("minecraft:acacia_wall_hanging_sign", "minecraft:acacia_hanging_sign");
        BLOCK_ITEM_OVERRIDE.put("minecraft:cherry_wall_hanging_sign", "minecraft:cherry_hanging_sign");
        BLOCK_ITEM_OVERRIDE.put("minecraft:jungle_wall_hanging_sign", "minecraft:jungle_hanging_sign");
        BLOCK_ITEM_OVERRIDE.put("minecraft:dark_oak_wall_hanging_sign", "minecraft:dark_oak_hanging_sign");
        BLOCK_ITEM_OVERRIDE.put("minecraft:pale_oak_wall_hanging_sign", "minecraft:pale_oak_hanging_sign");
        BLOCK_ITEM_OVERRIDE.put("minecraft:mangrove_wall_hanging_sign", "minecraft:mangrove_hanging_sign");
        BLOCK_ITEM_OVERRIDE.put("minecraft:bamboo_wall_hanging_sign", "minecraft:bamboo_hanging_sign");
        BLOCK_ITEM_OVERRIDE.put("minecraft:crimson_wall_hanging_sign", "minecraft:crimson_hanging_sign");
        BLOCK_ITEM_OVERRIDE.put("minecraft:warped_wall_hanging_sign", "minecraft:warped_hanging_sign");
        BLOCK_ITEM_OVERRIDE.put("minecraft:white_wall_banner", "minecraft:white_banner");
        BLOCK_ITEM_OVERRIDE.put("minecraft:orange_wall_banner", "minecraft:orange_banner");
        BLOCK_ITEM_OVERRIDE.put("minecraft:magenta_wall_banner", "minecraft:magenta_banner");
        BLOCK_ITEM_OVERRIDE.put("minecraft:light_blue_wall_banner", "minecraft:light_blue_banner");
        BLOCK_ITEM_OVERRIDE.put("minecraft:yellow_wall_banner", "minecraft:yellow_banner");
        BLOCK_ITEM_OVERRIDE.put("minecraft:lime_wall_banner", "minecraft:lime_banner");
        BLOCK_ITEM_OVERRIDE.put("minecraft:pink_wall_banner", "minecraft:pink_banner");
        BLOCK_ITEM_OVERRIDE.put("minecraft:gray_wall_banner", "minecraft:gray_banner");
        BLOCK_ITEM_OVERRIDE.put("minecraft:light_gray_wall_banner", "minecraft:light_gray_banner");
        BLOCK_ITEM_OVERRIDE.put("minecraft:cyan_wall_banner", "minecraft:cyan_banner");
        BLOCK_ITEM_OVERRIDE.put("minecraft:purple_wall_banner", "minecraft:purple_banner");
        BLOCK_ITEM_OVERRIDE.put("minecraft:blue_wall_banner", "minecraft:blue_banner");
        BLOCK_ITEM_OVERRIDE.put("minecraft:brown_wall_banner", "minecraft:brown_banner");
        BLOCK_ITEM_OVERRIDE.put("minecraft:green_wall_banner", "minecraft:green_banner");
        BLOCK_ITEM_OVERRIDE.put("minecraft:red_wall_banner", "minecraft:red_banner");
        BLOCK_ITEM_OVERRIDE.put("minecraft:black_wall_banner", "minecraft:black_banner");
        BLOCK_ITEM_OVERRIDE.put("minecraft:skeleton_wall_skull", "minecraft:skeleton_skull");
        BLOCK_ITEM_OVERRIDE.put("minecraft:wither_skeleton_wall_skull", "minecraft:wither_skeleton_skull");
        BLOCK_ITEM_OVERRIDE.put("minecraft:zombie_wall_head", "minecraft:zombie_head");
        BLOCK_ITEM_OVERRIDE.put("minecraft:player_wall_head", "minecraft:player_head");
        BLOCK_ITEM_OVERRIDE.put("minecraft:creeper_wall_head", "minecraft:creeper_head");
        BLOCK_ITEM_OVERRIDE.put("minecraft:dragon_wall_head", "minecraft:dragon_head");
        BLOCK_ITEM_OVERRIDE.put("minecraft:piglin_wall_head", "minecraft:piglin_head");
        BLOCK_ITEM_OVERRIDE.put("minecraft:copper_wall_torch", "minecraft:copper_torch");
        BLOCK_ITEM_OVERRIDE.put("minecraft:tripwire", "minecraft:string");
        BLOCK_ITEM_OVERRIDE.put("minecraft:torchflower_crop", "minecraft:torchflower_seeds");
        BLOCK_ITEM_OVERRIDE.put("minecraft:pitcher_crop", "minecraft:pitcher_pod");
        BLOCK_ITEM_OVERRIDE.put("minecraft:water_cauldron", "minecraft:cauldron");
        BLOCK_ITEM_OVERRIDE.put("minecraft:lava_cauldron", "minecraft:cauldron");
        BLOCK_ITEM_OVERRIDE.put("minecraft:powder_snow_cauldron", "minecraft:cauldron");
        BLOCK_ITEM_OVERRIDE.put("minecraft:big_dripleaf_stem", "minecraft:big_dripleaf");
        BLOCK_ITEM_OVERRIDE.put("minecraft:attached_melon_stem", "minecraft:melon_seeds");
        BLOCK_ITEM_OVERRIDE.put("minecraft:attached_pumpkin_stem", "minecraft:pumpkin_seeds");
        BLOCK_ITEM_OVERRIDE.put("minecraft:weeping_vines_plant", "minecraft:weeping_vines");
        BLOCK_ITEM_OVERRIDE.put("minecraft:twisting_vines_plant", "minecraft:twisting_vines");
        BLOCK_ITEM_OVERRIDE.put("minecraft:powder_snow", "minecraft:powder_snow_bucket");
        BLOCK_ITEM_OVERRIDE.put("minecraft:tube_coral_wall_fan", "minecraft:tube_coral_fan");
        BLOCK_ITEM_OVERRIDE.put("minecraft:dead_tube_coral_wall_fan", "minecraft:dead_tube_coral_fan");
        BLOCK_ITEM_OVERRIDE.put("minecraft:brain_coral_wall_fan", "minecraft:brain_coral_fan");
        BLOCK_ITEM_OVERRIDE.put("minecraft:dead_brain_coral_wall_fan", "minecraft:dead_brain_coral_fan");
        BLOCK_ITEM_OVERRIDE.put("minecraft:bubble_coral_wall_fan", "minecraft:bubble_coral_fan");
        BLOCK_ITEM_OVERRIDE.put("minecraft:dead_bubble_coral_wall_fan", "minecraft:dead_bubble_coral_fan");
        BLOCK_ITEM_OVERRIDE.put("minecraft:fire_coral_wall_fan", "minecraft:fire_coral_fan");
        BLOCK_ITEM_OVERRIDE.put("minecraft:dead_fire_coral_wall_fan", "minecraft:dead_fire_coral_fan");
        BLOCK_ITEM_OVERRIDE.put("minecraft:horn_coral_wall_fan", "minecraft:horn_coral_fan");
        BLOCK_ITEM_OVERRIDE.put("minecraft:dead_horn_coral_wall_fan", "minecraft:dead_horn_coral_fan");
    }

    /** 一方块拆两件物品（盆栽=花盆+植物；蜡烛蛋糕=蛋糕+蜡烛） */
    private static final Map<String, String[]> BLOCK_ITEM_SPLIT = new java.util.HashMap<>();
    static {
        BLOCK_ITEM_SPLIT.put("minecraft:potted_torchflower", new String[]{"minecraft:flower_pot", "minecraft:torchflower"});
        BLOCK_ITEM_SPLIT.put("minecraft:potted_oak_sapling", new String[]{"minecraft:flower_pot", "minecraft:oak_sapling"});
        BLOCK_ITEM_SPLIT.put("minecraft:potted_spruce_sapling", new String[]{"minecraft:flower_pot", "minecraft:spruce_sapling"});
        BLOCK_ITEM_SPLIT.put("minecraft:potted_birch_sapling", new String[]{"minecraft:flower_pot", "minecraft:birch_sapling"});
        BLOCK_ITEM_SPLIT.put("minecraft:potted_jungle_sapling", new String[]{"minecraft:flower_pot", "minecraft:jungle_sapling"});
        BLOCK_ITEM_SPLIT.put("minecraft:potted_acacia_sapling", new String[]{"minecraft:flower_pot", "minecraft:acacia_sapling"});
        BLOCK_ITEM_SPLIT.put("minecraft:potted_cherry_sapling", new String[]{"minecraft:flower_pot", "minecraft:cherry_sapling"});
        BLOCK_ITEM_SPLIT.put("minecraft:potted_dark_oak_sapling", new String[]{"minecraft:flower_pot", "minecraft:dark_oak_sapling"});
        BLOCK_ITEM_SPLIT.put("minecraft:potted_pale_oak_sapling", new String[]{"minecraft:flower_pot", "minecraft:pale_oak_sapling"});
        BLOCK_ITEM_SPLIT.put("minecraft:potted_mangrove_propagule", new String[]{"minecraft:flower_pot", "minecraft:mangrove_propagule"});
        BLOCK_ITEM_SPLIT.put("minecraft:potted_fern", new String[]{"minecraft:flower_pot", "minecraft:fern"});
        BLOCK_ITEM_SPLIT.put("minecraft:potted_dandelion", new String[]{"minecraft:flower_pot", "minecraft:dandelion"});
        BLOCK_ITEM_SPLIT.put("minecraft:potted_poppy", new String[]{"minecraft:flower_pot", "minecraft:poppy"});
        BLOCK_ITEM_SPLIT.put("minecraft:potted_blue_orchid", new String[]{"minecraft:flower_pot", "minecraft:blue_orchid"});
        BLOCK_ITEM_SPLIT.put("minecraft:potted_allium", new String[]{"minecraft:flower_pot", "minecraft:allium"});
        BLOCK_ITEM_SPLIT.put("minecraft:potted_azure_bluet", new String[]{"minecraft:flower_pot", "minecraft:azure_bluet"});
        BLOCK_ITEM_SPLIT.put("minecraft:potted_red_tulip", new String[]{"minecraft:flower_pot", "minecraft:red_tulip"});
        BLOCK_ITEM_SPLIT.put("minecraft:potted_orange_tulip", new String[]{"minecraft:flower_pot", "minecraft:orange_tulip"});
        BLOCK_ITEM_SPLIT.put("minecraft:potted_white_tulip", new String[]{"minecraft:flower_pot", "minecraft:white_tulip"});
        BLOCK_ITEM_SPLIT.put("minecraft:potted_pink_tulip", new String[]{"minecraft:flower_pot", "minecraft:pink_tulip"});
        BLOCK_ITEM_SPLIT.put("minecraft:potted_oxeye_daisy", new String[]{"minecraft:flower_pot", "minecraft:oxeye_daisy"});
        BLOCK_ITEM_SPLIT.put("minecraft:potted_cornflower", new String[]{"minecraft:flower_pot", "minecraft:cornflower"});
        BLOCK_ITEM_SPLIT.put("minecraft:potted_lily_of_the_valley", new String[]{"minecraft:flower_pot", "minecraft:lily_of_the_valley"});
        BLOCK_ITEM_SPLIT.put("minecraft:potted_wither_rose", new String[]{"minecraft:flower_pot", "minecraft:wither_rose"});
        BLOCK_ITEM_SPLIT.put("minecraft:potted_red_mushroom", new String[]{"minecraft:flower_pot", "minecraft:red_mushroom"});
        BLOCK_ITEM_SPLIT.put("minecraft:potted_brown_mushroom", new String[]{"minecraft:flower_pot", "minecraft:brown_mushroom"});
        BLOCK_ITEM_SPLIT.put("minecraft:potted_dead_bush", new String[]{"minecraft:flower_pot", "minecraft:dead_bush"});
        BLOCK_ITEM_SPLIT.put("minecraft:potted_cactus", new String[]{"minecraft:flower_pot", "minecraft:cactus"});
        BLOCK_ITEM_SPLIT.put("minecraft:potted_bamboo", new String[]{"minecraft:flower_pot", "minecraft:bamboo"});
        BLOCK_ITEM_SPLIT.put("minecraft:potted_crimson_fungus", new String[]{"minecraft:flower_pot", "minecraft:crimson_fungus"});
        BLOCK_ITEM_SPLIT.put("minecraft:potted_warped_fungus", new String[]{"minecraft:flower_pot", "minecraft:warped_fungus"});
        BLOCK_ITEM_SPLIT.put("minecraft:potted_crimson_roots", new String[]{"minecraft:flower_pot", "minecraft:crimson_roots"});
        BLOCK_ITEM_SPLIT.put("minecraft:potted_warped_roots", new String[]{"minecraft:flower_pot", "minecraft:warped_roots"});
        BLOCK_ITEM_SPLIT.put("minecraft:potted_azalea_bush", new String[]{"minecraft:flower_pot", "minecraft:azalea"});
        BLOCK_ITEM_SPLIT.put("minecraft:potted_flowering_azalea_bush", new String[]{"minecraft:flower_pot", "minecraft:flowering_azalea"});
        BLOCK_ITEM_SPLIT.put("minecraft:potted_open_eyeblossom", new String[]{"minecraft:flower_pot", "minecraft:open_eyeblossom"});
        BLOCK_ITEM_SPLIT.put("minecraft:potted_closed_eyeblossom", new String[]{"minecraft:flower_pot", "minecraft:closed_eyeblossom"});
        BLOCK_ITEM_SPLIT.put("minecraft:candle_cake", new String[]{"minecraft:cake", "minecraft:candle"});
        BLOCK_ITEM_SPLIT.put("minecraft:white_candle_cake", new String[]{"minecraft:cake", "minecraft:white_candle"});
        BLOCK_ITEM_SPLIT.put("minecraft:orange_candle_cake", new String[]{"minecraft:cake", "minecraft:orange_candle"});
        BLOCK_ITEM_SPLIT.put("minecraft:magenta_candle_cake", new String[]{"minecraft:cake", "minecraft:magenta_candle"});
        BLOCK_ITEM_SPLIT.put("minecraft:light_blue_candle_cake", new String[]{"minecraft:cake", "minecraft:light_blue_candle"});
        BLOCK_ITEM_SPLIT.put("minecraft:yellow_candle_cake", new String[]{"minecraft:cake", "minecraft:yellow_candle"});
        BLOCK_ITEM_SPLIT.put("minecraft:lime_candle_cake", new String[]{"minecraft:cake", "minecraft:lime_candle"});
        BLOCK_ITEM_SPLIT.put("minecraft:pink_candle_cake", new String[]{"minecraft:cake", "minecraft:pink_candle"});
        BLOCK_ITEM_SPLIT.put("minecraft:gray_candle_cake", new String[]{"minecraft:cake", "minecraft:gray_candle"});
        BLOCK_ITEM_SPLIT.put("minecraft:light_gray_candle_cake", new String[]{"minecraft:cake", "minecraft:light_gray_candle"});
        BLOCK_ITEM_SPLIT.put("minecraft:cyan_candle_cake", new String[]{"minecraft:cake", "minecraft:cyan_candle"});
        BLOCK_ITEM_SPLIT.put("minecraft:purple_candle_cake", new String[]{"minecraft:cake", "minecraft:purple_candle"});
        BLOCK_ITEM_SPLIT.put("minecraft:blue_candle_cake", new String[]{"minecraft:cake", "minecraft:blue_candle"});
        BLOCK_ITEM_SPLIT.put("minecraft:brown_candle_cake", new String[]{"minecraft:cake", "minecraft:brown_candle"});
        BLOCK_ITEM_SPLIT.put("minecraft:green_candle_cake", new String[]{"minecraft:cake", "minecraft:green_candle"});
        BLOCK_ITEM_SPLIT.put("minecraft:red_candle_cake", new String[]{"minecraft:cake", "minecraft:red_candle"});
        BLOCK_ITEM_SPLIT.put("minecraft:black_candle_cake", new String[]{"minecraft:cake", "minecraft:black_candle"});
    }

    /**
     * 数量型方块按状态数计实际物品数：蜡烛系 candles、海泡菜 pickles、海龟蛋 eggs、
     * 粉红花瓣/野花 flower_amount（均 1-4）。雪 layers 不折算（每方块按 1 计）。
     */
    private static int stateMultiplier(String name, Object propsObj) {
        if (name == null || name.isEmpty() || !(propsObj instanceof Map)) return 1;
        String path = name;
        int ci = name.lastIndexOf(':');
        if (ci >= 0) path = name.substring(ci + 1);
        String prop;
        if (path.equals("candle") || path.endsWith("_candle")) prop = "candles";
        else if (path.equals("sea_pickle")) prop = "pickles";
        else if (path.equals("turtle_egg")) prop = "eggs";
        else if (path.equals("pink_petals") || path.equals("wildflowers")) prop = "flower_amount";
        else return 1;
        Object v = ((Map<?, ?>) propsObj).get(prop);
        if (v == null) return 1;
        try {
            return Math.max(1, Math.min(4, Integer.parseInt(v.toString())));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /** 方块→物品：原版 asItem > 单件折算 > 拆件；都不可换算返回 null */
    private static Item[] blockToItems(String blockName) {
        try {
            String ns = "minecraft", path = blockName;
            int ci = blockName.indexOf(':');
            if (ci >= 0) {
                ns = blockName.substring(0, ci);
                path = blockName.substring(ci + 1);
            }
            Identifier id = Identifier.fromNamespaceAndPath(ns, path);
            Block block = BuiltInRegistries.BLOCK.getValue(id);
            if (block != null) {
                Item item = block.asItem();
                if (item != null && item != Items.AIR) return new Item[]{item};
            }
            String override = BLOCK_ITEM_OVERRIDE.get(blockName);
            if (override != null) {
                Item oi = BuiltInRegistries.ITEM.getValue(Identifier.parse(override));
                if (oi != null && oi != Items.AIR) return new Item[]{oi};
            }
            String[] split = BLOCK_ITEM_SPLIT.get(blockName);
            if (split != null) {
                ArrayList<Item> list = new ArrayList<>(split.length);
                for (String part : split) {
                    Item si = BuiltInRegistries.ITEM.getValue(Identifier.parse(part));
                    if (si != null && si != Items.AIR) list.add(si);
                }
                if (!list.isEmpty()) return list.toArray(new Item[0]);
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }
}
