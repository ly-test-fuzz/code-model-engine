/*
 * GPLv3 License
 *
 * Copyright (c) 2022-2026 4ra1n (Jar Analyzer Team)
 *
 * This project is distributed under the GPLv3 license.
 *
 * https://github.com/jar-analyzer/jar-analyzer/blob/master/LICENSE
 */

package me.n1ar4.jar.analyzer.engine;

import me.n1ar4.jar.analyzer.engine.log.LogManager;
import me.n1ar4.jar.analyzer.engine.log.Logger;

/**
 * 引擎构建进度回调
 * GUI 模式下可用于更新进度条和日志面板
 * CLI 模式下可用于打印进度信息
 */
@SuppressWarnings("all")
public interface ProgressCallback {
    /**
     * 更新进度百分比
     *
     * @param percent 0-100
     */
    void onProgress(int percent);

    /**
     * 输出信息日志
     */
    void onInfo(String message);

    /**
     * 输出警告日志
     */
    void onWarn(String message);

    /**
     * 输出错误日志
     */
    void onError(String message);

    /**
     * 统计信息回调
     */
    void onStats(String key, String value);

    /**
     * 默认的控制台回调（CLI 模式使用）
     */
    ProgressCallback CONSOLE = new ProgressCallback() {
        private final Logger logger = LogManager.getLogger();

        @Override
        public void onProgress(int percent) {
            logger.info("[ENGINE] Progress: {}%", percent);
        }

        @Override
        public void onInfo(String message) {
            logger.info("[ENGINE] INFO: {}", message);
        }

        @Override
        public void onWarn(String message) {
            logger.warn("[ENGINE] WARN: {}", message);
        }

        @Override
        public void onError(String message) {
            logger.error("[ENGINE] ERROR: {}", message);
        }

        @Override
        public void onStats(String key, String value) {
            logger.info("[ENGINE] {}: {}", key, value);
        }
    };
}
