package com.sthstrange.projectmemo.client;

import net.minecraft.client.resources.language.I18n;

/**
 * i18n helper：所有 UI 文案一律走这里，禁止在代码里硬编码界面文字。
 * 语言选择=MC 客户端语言：zh_cn/zh_tw/lzh 走中文语言文件，其余语言自动回退 en_us。
 */
public final class L10n {
    private L10n() { }

    public static String get(String key, Object... args) {
        return I18n.get(key, args);
    }
}
