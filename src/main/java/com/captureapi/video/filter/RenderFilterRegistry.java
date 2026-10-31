package com.captureapi.video.filter;

import com.captureapi.core.config.CaptureParams;
import com.captureapi.core.constant.CaptureConstants;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 渲染黑白名单（T8.3 / I8.3.1）。
 *
 * <p>文本文件（每行一个注册名；`#` 开头为标签，首版保留原样不解析）；白名单非空时仅白名单内
 * 允许，黑名单在白名单内挖洞（白减交）。当前提供集合编译/热重载/查询 API；离屏场景收集阶段
 * 的实际接入（I8.3.2）待渲染收集 Mixin/入口确认（W-095），未接入前不影响渲染。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class RenderFilterRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(CaptureConstants.LOG_MARKER);
    private static final RenderFilterRegistry INSTANCE = new RenderFilterRegistry();

    private final AtomicLong blockedQueries = new AtomicLong();
    private volatile Rules rules = Rules.empty();

    private RenderFilterRegistry() {
    }

    public static RenderFilterRegistry get() {
        return INSTANCE;
    }

    /**
     * 重载名单（文件不存在则创建模板）。
     *
     * @return 载入统计描述
     */
    public synchronized String reload() {
        Path black = FMLPaths.GAMEDIR.get().resolve(CaptureParams.get().renderBlacklistFile());
        Path white = FMLPaths.GAMEDIR.get().resolve(CaptureParams.get().renderWhitelistFile());
        Set<String> blackSet = readSet(black, "渲染黑名单");
        Set<String> whiteSet = readSet(white, "渲染白名单");
        rules = new Rules(blackSet, whiteSet);
        String summary = "black=" + blackSet.size() + ", white=" + whiteSet.size();
        LOGGER.info("{} [RENDERFILTER] 名单重载：{}（tag 条目首版仅存不解析）", CaptureConstants.LOG_MARKER, summary);
        return summary;
    }

    /** @return 当前规则统计 */
    public String summary() {
        return rules.summary();
    }

    /** @return 原版注册名是否允许进入离屏渲染（当前未接入收集阶段） */
    public boolean allows(String id) {
        if (id == null) {
            return true;
        }
        if (!rules.white().isEmpty() && !rules.white().contains(id)) {
            blockedQueries.incrementAndGet();
            return false;
        }
        boolean allowed = !rules.black().contains(id);
        if (!allowed) {
            blockedQueries.incrementAndGet();
        }
        return allowed;
    }

    public long blockedQueries() {
        return blockedQueries.get();
    }

    private Set<String> readSet(Path path, String label) {
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            if (!Files.exists(path)) {
                Files.writeString(path, "# CaptureAPI " + label + "（每行一个注册名；# 开头为标签，首版保留不解析）\n",
                        StandardCharsets.UTF_8);
                return Collections.emptySet();
            }
            Set<String> set = new LinkedHashSet<>();
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#") && !trimmed.startsWith("#minecraft:")) {
                    if (trimmed.startsWith("#")) {
                        // 标签条目保留（首版不解析）
                        set.add(trimmed.substring(1).trim());
                    }
                    continue;
                }
                set.add(trimmed);
            }
            return Collections.unmodifiableSet(set);
        } catch (IOException e) {
            LOGGER.warn("{} [RENDERFILTER] 读取 {} 失败，保留旧规则：{}", CaptureConstants.LOG_MARKER, label,
                    e.getMessage());
            return rules.black();
        }
    }

    /** 编译规则（白名单非空则白名单优先，黑名单挖洞）。 */
    private record Rules(Set<String> black, Set<String> white) {
        private static Rules empty() {
            return new Rules(Set.of(), Set.of());
        }

        private String summary() {
            return "black=" + black.size() + ", white=" + white.size();
        }
    }
}
