package com.captureapi.audio;

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
 * 音频黑白名单（T8.4 / I8.4.1）。
 *
 * <p>文本名单（每行 SoundEvent 注册名/标签），白减交语义；当前提供集合编译/热重载/查询 API。
 * 采集侧挂接（SoundEngine 播放入口）待实现（需 Mixin；过滤只影响环回捕获、玩家照常发声）。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class AudioFilterRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(CaptureConstants.LOG_MARKER);
    private static final AudioFilterRegistry INSTANCE = new AudioFilterRegistry();

    private final AtomicLong blockedQueries = new AtomicLong();
    private volatile Rules rules = Rules.empty();

    private AudioFilterRegistry() {
    }

    public static AudioFilterRegistry get() {
        return INSTANCE;
    }

    /** 重载名单（文件不存在则创建模板）。 */
    public synchronized String reload() {
        Path black = FMLPaths.GAMEDIR.get().resolve(CaptureParams.get().audioBlacklistFile());
        Path white = FMLPaths.GAMEDIR.get().resolve(CaptureParams.get().audioWhitelistFile());
        Set<String> blackSet = readSet(black, "音频黑名单");
        Set<String> whiteSet = readSet(white, "音频白名单");
        rules = new Rules(blackSet, whiteSet);
        String summary = "black=" + blackSet.size() + ", white=" + whiteSet.size();
        LOGGER.info("{} [AUDIOFILTER] 名单重载：{}（采集侧挂接待实现）", CaptureConstants.LOG_MARKER, summary);
        return summary;
    }

    public String summary() {
        return "black=" + rules.black().size() + ", white=" + rules.white().size();
    }

    /** @return 声音注册名是否允许进入捕获（当前未接入采集侧） */
    public boolean allows(String soundId) {
        if (soundId == null) {
            return true;
        }
        if (!rules.white().isEmpty() && !rules.white().contains(soundId)) {
            blockedQueries.incrementAndGet();
            return false;
        }
        boolean allowed = !rules.black().contains(soundId);
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
                Files.writeString(path, "# CaptureAPI " + label + "（每行一个 SoundEvent 注册名）\n",
                        StandardCharsets.UTF_8);
                return Collections.emptySet();
            }
            Set<String> set = new LinkedHashSet<>();
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                set.add(trimmed.startsWith("#") ? trimmed.substring(1) : trimmed);
            }
            return Collections.unmodifiableSet(set);
        } catch (IOException e) {
            LOGGER.warn("{} [AUDIOFILTER] 读取 {} 失败，保留旧规则：{}", CaptureConstants.LOG_MARKER, label,
                    e.getMessage());
            return rules.black();
        }
    }

    private record Rules(Set<String> black, Set<String> white) {
        private static Rules empty() {
            return new Rules(Set.of(), Set.of());
        }
    }
}
