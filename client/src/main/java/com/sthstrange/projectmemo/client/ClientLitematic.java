package com.sthstrange.projectmemo.client;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 客户端本地 .litematic 解析：方块统计 → 物品需求表（block.asItem 映射，红石线→红石粉等）。 */
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
            out.error = "投影文件解析失败: " + e.getMessage();
            return out;
        }
        Object regionsObj = root.get("Regions");
        if (!(regionsObj instanceof Map)) {
            out.error = "不是有效的 litematic 文件（缺少 Regions）";
            return out;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> regions = (Map<String, Object>) regionsObj;
        if (regions.isEmpty()) {
            out.error = "投影为空（没有区域）";
            return out;
        }
        for (Map.Entry<String, Object> e : regions.entrySet()) {
            if (!(e.getValue() instanceof Map)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> region = (Map<String, Object>) e.getValue();
            try {
                parseRegion(region, out);
            } catch (Exception ex) {
                out.error = "投影区域解析失败: " + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
                return out;
            }
        }
        if (out.counts.isEmpty() && out.error == null) out.error = "投影里没有可作为物品的方块";
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
        if (volume > 64_000_000) { out.error = "投影区域过大（>6400 万方块）"; return; }

        int bits = Math.max(1, 32 - Integer.numberOfLeadingZeros(palette.size() - 1));
        long mask = (1L << bits) - 1;

        String[] names = new String[palette.size()];
        for (int i = 0; i < palette.size(); i++) {
            Object entry = palette.get(i);
            if (entry instanceof Map) {
                Object name = ((Map<String, Object>) entry).get("Name");
                names[i] = name == null ? "" : name.toString();
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
            Item item = blockToItem(name);
            if (item == null || item == Items.AIR) {
                out.skippedNonItem++;
                continue;
            }
            String itemId = BuiltInRegistries.ITEM.getKey(item).toString();
            out.counts.merge(itemId, 1L, Long::sum);
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

    /** 方块无物品形态时的常见物品映射（与服务端 LitematicImporter 一致） */
    private static final java.util.Map<String, String> BLOCK_ITEM_OVERRIDE = new java.util.HashMap<>();
    static {
        BLOCK_ITEM_OVERRIDE.put("minecraft:redstone_wire", "minecraft:redstone");
        BLOCK_ITEM_OVERRIDE.put("minecraft:wheat", "minecraft:wheat_seeds");
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
        BLOCK_ITEM_OVERRIDE.put("minecraft:nether_wart", "minecraft:nether_wart");
        BLOCK_ITEM_OVERRIDE.put("minecraft:wall_torch", "minecraft:torch");
        BLOCK_ITEM_OVERRIDE.put("minecraft:redstone_wall_torch", "minecraft:redstone_torch");
        BLOCK_ITEM_OVERRIDE.put("minecraft:soul_wall_torch", "minecraft:soul_torch");
    }

    private static Item blockToItem(String blockName) {
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
                if (item != null && item != Items.AIR) return item;
            }
            String override = BLOCK_ITEM_OVERRIDE.get(blockName);
            if (override != null) {
                Item oi = BuiltInRegistries.ITEM.getValue(Identifier.parse(override));
                if (oi != null && oi != Items.AIR) return oi;
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }
}
