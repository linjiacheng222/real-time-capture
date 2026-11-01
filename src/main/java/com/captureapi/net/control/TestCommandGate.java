package com.captureapi.net.control;

import com.captureapi.core.constant.CaptureConstants;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 测试专用 WS 指令门控（发布安全）。
 *
 * <p>仅当满足以下任一条件时放行 test-only 指令（client.* / audio.section / shader.status）：</p>
 * <ul>
 *   <li>JVM 系统属性 {@code captureapi.debug.selftest=true} 或
 *       {@code captureapi.debug.test_commands=true}（dev 运行配置已设置前者）；</li>
 *   <li>游戏目录存在标记文件 {@code config/captureapi/test_commands.enabled}。</li>
 * </ul>
 *
 * <p>发布 jar 默认两者都不满足，测试指令返回 {@code E_TEST_COMMANDS_DISABLED}；
 * 用户测试前运行 {@code scripts/enable-test-commands.ps1 -GameDir <实例目录>} 创建标记，
 * 测试后删除即可。正常相机/录像/配置等指令不受影响。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class TestCommandGate {

    private static final Logger LOGGER = LoggerFactory.getLogger(CaptureConstants.LOG_MARKER);
    /** test-only action 清单（与 release-gate-check.ps1 保持一致）。 */
    public static final Set<String> TEST_ACTIONS = Set.of(
            "client.unpause", "client.playerPose", "client.screenshot", "client.leaveWorld",
            "client.loadWorld", "client.playTestSound", "client.playPosSound",
            "audio.section", "shader.status");
    private static final String FLAG_FILE = "config/captureapi/test_commands.enabled";

    private TestCommandGate() {
        throw new AssertionError("no instances");
    }

    public static boolean isTestAction(String action) {
        return action != null && TEST_ACTIONS.contains(action);
    }

    /** @return 是否允许 test-only 指令 */
    public static boolean enabled() {
        try {
            if (Boolean.getBoolean("captureapi.debug.selftest")
                    || Boolean.getBoolean("captureapi.debug.test_commands")) {
                return true;
            }
            Path flag = FMLPaths.GAMEDIR.get().resolve(FLAG_FILE);
            return Files.isRegularFile(flag);
        } catch (Throwable t) {
            LOGGER.debug("{} [TESTGATE] 标记文件检查失败，按禁用处理：{}", CaptureConstants.LOG_MARKER, t.getMessage());
            return false;
        }
    }

    /** 由 dispatch 入口调用；禁用时抛出结构化错误。 */
    public static void require(String action) {
        if (isTestAction(action) && !enabled()) {
            throw new ControlCommandDispatcher.ControlException(ControlErrorCode.E_TEST_COMMANDS_DISABLED,
                    "测试指令已禁用（需 dev 属性或 config/captureapi/test_commands.enabled）：" + action);
        }
    }
}
