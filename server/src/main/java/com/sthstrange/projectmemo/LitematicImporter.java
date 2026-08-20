package com.sthstrange.projectmemo;

import org.bukkit.Material;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * .litematic 投影导入（参考 PCHSystem 逻辑）：解析 BlockStatePalette + BlockStates，
 * 统计方块数量 → 生成材料行；记录投影原点（区域保存时的世界坐标）。
 * 非物品方块（空气/传送门等）跳过并计数。
 */
public final class LitematicImporter {

    public static final class ImportResult {
        public final LinkedHashMap<Material, Long> counts = new LinkedHashMap<>();
        public long totalBlocks;
        public long skippedNonItem;
        public boolean hasOrigin;
        public int originX, originY, originZ;
        public String error;
    }

    public static ImportResult parse(File file) {
        ImportResult res = new ImportResult();
        Map<String, Object> root;
        try {
            root = NbtReader.readGzip(file);
        } catch (Exception e) {
            res.error = "投影文件解析失败: " + e.getMessage();
            return res;
        }
        Object regionsObj = root.get("Regions");
        if (!(regionsObj instanceof Map)) {
            res.error = "不是有效的 litematic 文件（缺少 Regions）";
            return res;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> regions = (Map<String, Object>) regionsObj;
        if (regions.isEmpty()) {
            res.error = "投影为空（没有区域）";
            return res;
        }
        for (Map.Entry<String, Object> e : regions.entrySet()) {
            if (!(e.getValue() instanceof Map)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> region = (Map<String, Object>) e.getValue();
            parseRegion(region, res);
        }
        if (res.counts.isEmpty() && res.error == null) res.error = "投影里没有可作为物品的方块";
        return res;
    }

    @SuppressWarnings("unchecked")
    private static void parseRegion(Map<String, Object> region, ImportResult res) {
        // 原点（取第一个带 Position 的区域；兼容 compound{x,y,z} / list[x,y,z] 旧格式）
        if (!res.hasOrigin) {
            int[] pos = xyz(region.get("Position"));
            if (pos != null) {
                res.hasOrigin = true;
                res.originX = pos[0];
                res.originY = pos[1];
                res.originZ = pos[2];
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
        if (volume > 64_000_000) { res.error = "投影区域过大（>6400万方块）"; return; }

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
            res.totalBlocks++;
            if (name.isEmpty() || name.endsWith(":air") || name.endsWith(":void_air") || name.endsWith(":cave_air"))
                continue;
            Material mat = blockToItem(name);
            if (mat == null) { res.skippedNonItem++; continue; }
            res.counts.merge(mat, 1L, Long::sum);
        }
    }

    /** 方块无物品形态时的常见物品映射（红石线→红石粉等），找不到返回 null */
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

    private static Material blockToItem(String name) {
        Material mat = Material.matchMaterial(name);
        if (mat != null && mat.isItem()) return mat;
        String override = BLOCK_ITEM_OVERRIDE.get(name);
        if (override != null) {
            Material m2 = Material.matchMaterial(override);
            if (m2 != null && m2.isItem()) return m2;
        }
        return null;
    }

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
}
