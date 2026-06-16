/*
 * GPLv3 License
 *
 * Copyright (c) 2022-2026 4ra1n (Jar Analyzer Team)
 *
 * This project is distributed under the GPLv3 license.
 *
 * https://github.com/jar-analyzer/jar-analyzer/blob/master/LICENSE
 */

package me.n1ar4.jar.analyzer.engine.utils;

import java.util.ArrayList;

/**
 * 黑白名单配置解析（从 gui.util.ListParser 迁移，无 GUI 依赖）
 */
public class ListParser {
    /**
     * 黑白名单配置解析
     * 支持以下多种：
     * # 注释
     * // 注释
     * /* 注释
     * com.a.
     * com.a.Demo
     * com.a.;
     * com.a.Demo;
     * com.a.;com.b.;com.c.Demo;
     *
     * @param text 配置字符串
     * @return 解析后的结果
     */
    public static ArrayList<String> parse(String text) {
        text = text.trim();
        String[] temp = text.split("\n");
        if (temp.length == 0) {
            return new ArrayList<>();
        }
        ArrayList<String> list = new ArrayList<>();
        for (String s : temp) {
            if (s == null) {
                continue;
            }
            s = s.trim();
            if (s.isEmpty()) {
                continue;
            }
            if (s.endsWith("\r")) {
                s = s.substring(0, s.length() - 1);
            }
            if (s.startsWith("#") || s.startsWith("/*") || s.startsWith("//")) {
                continue;
            }
            if (s.contains(";")) {
                String[] items = s.split(";");
                if (items.length == 1) {
                    s = items[0];
                } else {
                    for (String item : items) {
                        if (item == null) {
                            continue;
                        }
                        item = item.trim();
                        if (item.isEmpty()) {
                            continue;
                        }
                        while (item.endsWith("*")) {
                            item = item.substring(0, item.length() - 1);
                        }
                        item = item.replace(".", "/");
                        list.add(item);
                    }
                    continue;
                }
            }
            while (s.endsWith("*")) {
                s = s.substring(0, s.length() - 1);
            }
            s = s.replace(".", "/");
            list.add(s);
        }
        return list;
    }
}
