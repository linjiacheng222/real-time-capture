package com.captureapi.core;

import com.captureapi.CaptureInterface;
import com.captureapi.core.config.RuntimeConfigWriter;
import com.captureapi.core.constant.CaptureConstants;
import com.captureapi.video.camera.CameraCreateParams;
import com.captureapi.video.camera.CameraManager;
import com.captureapi.video.camera.CameraSpec;
import com.captureapi.video.filter.FilterRegistry;
import com.captureapi.video.filter.RenderFilterRegistry;
import com.captureapi.video.overlay.OverlayModel;
import com.captureapi.video.overlay.OverlayRegistry;
import com.captureapi.audio.AudioFilterRegistry;
import com.captureapi.net.NetworkServiceManager;
import com.captureapi.net.recording.RecordingParams;
import com.captureapi.net.recording.RecordingSession;
import com.captureapi.net.recording.RecordingStatus;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 游戏内 /captureapi 指令集 v2——函数式设计。
 *
 * <p>核心理念：指令＝函数调用，参数＝可选键值对。一条 capture 指令携带全部所需参数，
 * 不依赖全局状态 setter。滤镜/叠加/分辨率/质量等全部作为 capture 或 camera 的可选参数传入。</p>
 *
 * <p>参数格式：空格分隔的 key:value 对，如：
 * {@code pos:100,64,200 rot:45,-10 fov:90 filter:grayscale overlay:watermark@0.9,0.1,0.3,1,0,0.8 quality:85}</p>
 */
@Mod.EventBusSubscriber(modid = CaptureInterface.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CaptureCommand {

    private static final Logger LOGGER = LoggerFactory.getLogger(CaptureConstants.LOG_MARKER);

    private CaptureCommand() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(build());
        LOGGER.info("{} [CMD] /captureapi v2 指令集已注册（函数式参数设计）", CaptureConstants.LOG_MARKER);
    }

    // =====================================================================
    // 指令树
    // =====================================================================

    private static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("captureapi")
                // 核心操作（函数式）
                .then(captureTree())
                .then(cameraTree())
                .then(recordTree())
                // 管理（列出/重载）
                .then(Commands.literal("filter")
                        .then(Commands.literal("list").executes(CaptureCommand::filterList))
                        .then(Commands.literal("reload").executes(CaptureCommand::filterReload)))
                .then(Commands.literal("overlay")
                        .then(Commands.literal("list").executes(CaptureCommand::overlayList))
                        .then(Commands.literal("reload").executes(CaptureCommand::overlayReload)))
                .then(Commands.literal("renderfilter")
                        .then(Commands.literal("list").executes(CaptureCommand::renderFilterList))
                        .then(Commands.literal("reload").executes(CaptureCommand::renderFilterReload)))
                .then(Commands.literal("audiofilter")
                        .then(Commands.literal("list").executes(CaptureCommand::audioFilterList))
                        .then(Commands.literal("reload").executes(CaptureCommand::audioFilterReload)))
                // 信息查询
                .then(Commands.literal("info")
                        .then(Commands.literal("health").executes(CaptureCommand::health))
                        .then(Commands.literal("frame").executes(CaptureCommand::frameInfo))
                        .then(Commands.literal("shader").executes(CaptureCommand::shaderStatus))
                        .then(Commands.literal("config")
                                .executes(CaptureCommand::configGetAll)
                                .then(Commands.argument("key", StringArgumentType.string())
                                        .executes(CaptureCommand::configGetOne)))
                        .then(Commands.literal("audio")
                                .then(Commands.argument("x", com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg())
                                        .then(Commands.argument("y", com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg())
                                                .then(Commands.argument("z", com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg())
                                                        .executes(CaptureCommand::audioSection))))))
                // 参数写入（全局默认值，capture 可覆盖）
                .then(Commands.literal("set")
                        .then(Commands.argument("key", StringArgumentType.string())
                                .then(Commands.argument("value", StringArgumentType.greedyString())
                                        .executes(CaptureCommand::configSetOne))))
                .then(Commands.literal("help").executes(CaptureCommand::help));
    }

    // =====================================================================
    // capture —— 一次性帧获取（核心函数）
    // =====================================================================

    private static LiteralArgumentBuilder<CommandSourceStack> captureTree() {
        return Commands.literal("capture")
                .executes(ctx -> doCapture(ctx, ""))
                .then(Commands.argument("params", StringArgumentType.greedyString())
                        .executes(ctx -> doCapture(ctx, StringArgumentType.getString(ctx, "params"))));
    }

    /**
     * 执行一次性帧获取。
     *
     * <p>可用参数（全部可选，不填取 active 相机或全局默认）：</p>
     * <ul>
     *   <li>pos:x,y,z — 相机位置</li>
     *   <li>rot:yaw,pitch[,roll] — 朝向（度）</li>
     *   <li>fov:度数 — 视场角</li>
     *   <li>filter:名称 — 滤镜（none=无）</li>
     *   <li>overlay:名称[@x,y,scaleX,scaleY,rotation,opacity] — 叠加图层</li>
     *   <li>quality:0-100 — JPEG 质量</li>
     *   <li>res:宽x高 — 分辨率</li>
     *   <li>audio:秒数 — 含音频截面</li>
     *   <li>out:文件路径 — 输出到文件</li>
     * </ul>
     */
    private static int doCapture(CommandContext<CommandSourceStack> ctx, String paramString) {
        try {
            Map<String, String> p = ParamParser.parse(paramString);
            List<String> warnings = new ArrayList<>();

            // 相机参数
            CameraSpec active = CameraManager.get().active();
            double x, y, z, yaw, pitch, roll, fov;
            if (active != null) {
                x = active.positionX(); y = active.positionY(); z = active.positionZ();
                yaw = active.yawDegrees(); pitch = active.pitchDegrees();
                roll = active.rollDegrees(); fov = active.fovDegrees();
            } else {
                var player = ctx.getSource().getPlayer();
                if (player != null) {
                    x = player.position().x; y = player.position().y; z = player.position().z;
                    yaw = player.getYRot(); pitch = player.getXRot();
                } else { x = 0; y = 64; z = 0; yaw = 0; pitch = 0; }
                roll = 0; fov = 70;
            }
            double[] pos = ParamParser.getVec3(p, "pos", x, y, z);
            double[] rot = ParamParser.getVec3(p, "rot", yaw, pitch, roll);
            fov = ParamParser.getDouble(p, "fov", fov, 1, 179);

            // 构建统一 FrameRequest（与 WS/Java API 同一函数）
            var builder = com.captureapi.net.FrameRequest.builder()
                    .position(pos[0], pos[1], pos[2])
                    .rotation((float) rot[0], (float) rot[1], (float) rot[2])
                    .fov(fov);

            // 滤镜
            String filterName = p.get("filter");
            if (filterName != null) builder.filter(filterName);

            // 叠加
            String overlaySpec = p.get("overlay");
            if (overlaySpec != null) {
                var model = ParamParser.parseOverlay(overlaySpec);
                if (model != null) builder.overlay(model);
                else warnings.add("叠加格式错误：" + overlaySpec);
            }

            // 质量/分辨率
            int quality = (int) ParamParser.getDouble(p, "quality", -1, 1, 100);
            if (quality > 0) builder.quality(quality);
            String res = p.get("res");
            if (res != null) {
                try {
                    String[] wh = res.toLowerCase().split("x");
                    builder.resolution(Integer.parseInt(wh[0].trim()), Integer.parseInt(wh[1].trim()));
                } catch (Exception e) { warnings.add("分辨率格式错误：" + res + "（正确：1280x1024）"); }
            }

            // 音频
            double audioSec = ParamParser.getDouble(p, "audio", 0, 0, 60);
            if (audioSec > 0) builder.audio(true, (int) Math.ceil(audioSec * 100));

            // 输出
            String outPath = p.get("out");
            if (outPath != null) builder.outputPath(java.nio.file.Path.of(outPath));

            // ★ 调用统一函数（与 WS capture action / Java API 同一入口）
            var outcome = com.captureapi.net.CaptureApi.capture(builder.build());

            var frame = outcome.frame();
            StringBuilder sb = new StringBuilder();
            sb.append("§a截取成功§r ").append(frame.width()).append("x").append(frame.height())
                    .append(" ts=").append(frame.timestampNanos())
                    .append(" ").append(frame.sizeBytes()).append("B");
            if (frame.isFile()) sb.append(" §b→ ").append(frame.location());
            if (outcome.audio() != null) {
                sb.append(" §d+音频 ").append(outcome.audio().sampleCount()).append("采样");
            }
            ok(ctx, sb.toString());

            for (String w : warnings) err(ctx, "§e⚠ " + w + "§r");
            return 1;
        } catch (Exception e) {
            err(ctx, "截取失败：" + e.getMessage());
            return 0;
        }
    }

    // =====================================================================
    // camera —— 持久相机管理（CRUD）
    // =====================================================================

    private static LiteralArgumentBuilder<CommandSourceStack> cameraTree() {
        return Commands.literal("camera")
                .then(Commands.literal("create")
                        .executes(ctx -> doCameraCreate(ctx, ""))
                        .then(Commands.argument("params", StringArgumentType.greedyString())
                                .executes(ctx -> doCameraCreate(ctx, StringArgumentType.getString(ctx, "params")))))
                .then(Commands.literal("adjust")
                        .then(Commands.argument("id", IntegerArgumentType.integer(1))
                                .executes(ctx -> doCameraAdjust(ctx, ""))
                                .then(Commands.argument("params", StringArgumentType.greedyString())
                                        .executes(ctx -> doCameraAdjust(ctx, StringArgumentType.getString(ctx, "params"))))))
                .then(Commands.literal("list").executes(CaptureCommand::cameraList))
                .then(Commands.literal("status").executes(CaptureCommand::cameraStatus))
                .then(Commands.literal("activate")
                        .then(Commands.argument("id", IntegerArgumentType.integer(1))
                                .executes(CaptureCommand::cameraActivate)))
                .then(Commands.literal("pause")
                        .then(Commands.argument("id", IntegerArgumentType.integer(1))
                                .executes(ctx -> cameraPauseResume(ctx, true))))
                .then(Commands.literal("resume")
                        .then(Commands.argument("id", IntegerArgumentType.integer(1))
                                .executes(ctx -> cameraPauseResume(ctx, false))))
                .then(Commands.literal("delete")
                        .then(Commands.argument("id", IntegerArgumentType.integer(1))
                                .executes(CaptureCommand::cameraDelete)));
    }

    private static int doCameraCreate(CommandContext<CommandSourceStack> ctx, String paramString) {
        try {
            Map<String, String> p = ParamParser.parse(paramString);
            var player = ctx.getSource().getPlayer();
            double x = 0, y = 64, z = 0, yaw = 0, pitch = 0, roll = 0, fov = 70;
            if (player != null) {
                x = player.position().x; y = player.position().y; z = player.position().z;
                yaw = player.getYRot(); pitch = player.getXRot();
            }
            double[] pos = ParamParser.getVec3(p, "pos", x, y, z);
            double[] rot = ParamParser.getVec3(p, "rot", yaw, pitch, roll);
            fov = ParamParser.getDouble(p, "fov", fov, 1, 179);
            var builder = CameraCreateParams.builder()
                    .position(pos[0], pos[1], pos[2])
                    .rotation((float) rot[0], (float) rot[1], (float) rot[2])
                    .fov(fov);
            // res
            String res = p.get("res");
            if (res != null) {
                try {
                    String[] wh = res.toLowerCase().split("x");
                    builder.resolution(Integer.parseInt(wh[0].trim()), Integer.parseInt(wh[1].trim()));
                } catch (Exception e) { err(ctx, "分辨率格式错误：" + res); }
            }
            // bound
            String bound = p.get("bound");
            if (bound != null) {
                try {
                    if (bound.contains("@")) {
                        String[] parts = bound.split("@", 2);
                        var uuid = java.util.UUID.fromString(parts[0].trim());
                        String[] offsets = parts[1].split(",");
                        builder.boundEntity(uuid,
                                Double.parseDouble(offsets[0]), Double.parseDouble(offsets[1]),
                                Double.parseDouble(offsets[2]));
                    } else {
                        builder.boundEntity(java.util.UUID.fromString(bound.trim()), 0, 0, 0);
                    }
                } catch (Exception e) { err(ctx, "绑定格式错误：" + bound + "（正确：UUID 或 UUID@ox,oy,oz）"); }
            }
            CameraSpec spec = CameraManager.get().create(builder.build());
            ok(ctx, "相机创建 #" + spec.id() + " (" + f2(spec.positionX()) + "," + f2(spec.positionY()) + "," + f2(spec.positionZ())
                    + ") fov=" + f2(spec.fovDegrees()) + " " + spec.width() + "x" + spec.height());
            // 滤镜/叠加作为相机级设置
            applyFilterOverlay(ctx, p);
            return 1;
        } catch (Exception e) { err(ctx, e.getMessage()); return 0; }
    }

    private static int doCameraAdjust(CommandContext<CommandSourceStack> ctx, String paramString) {
        int id;
        try { id = IntegerArgumentType.getInteger(ctx, "id"); }
        catch (Exception e) { err(ctx, "缺少相机 id"); return 0; }
        CameraSpec old = CameraManager.get().get(id);
        if (old == null) { err(ctx, "相机不存在：" + id); return 0; }
        try {
            Map<String, String> p = ParamParser.parse(paramString);
            double x = old.positionX(), y = old.positionY(), z = old.positionZ();
            double yaw = old.yawDegrees(), pitch = old.pitchDegrees(), roll = old.rollDegrees();
            double fov = old.fovDegrees();
            double[] pos = ParamParser.getVec3(p, "pos", x, y, z);
            double[] rot = ParamParser.getVec3(p, "rot", yaw, pitch, roll);
            fov = ParamParser.getDouble(p, "fov", fov, 1, 179);
            var builder = CameraCreateParams.builder()
                    .position(pos[0], pos[1], pos[2])
                    .rotation((float) rot[0], (float) rot[1], (float) rot[2])
                    .fov(fov);
            String res = p.get("res");
            if (res != null) {
                String[] wh = res.toLowerCase().split("x");
                builder.resolution(Integer.parseInt(wh[0].trim()), Integer.parseInt(wh[1].trim()));
            } else {
                builder.resolution(old.width(), old.height());
            }
            CameraManager.get().adjust(id, builder.build());
            applyFilterOverlay(ctx, p);
            ok(ctx, "相机 #" + id + " 已调整");
            return 1;
        } catch (Exception e) { err(ctx, e.getMessage()); return 0; }
    }

    /** 滤镜/叠加参数应用（capture 和 camera 共用） */
    private static void applyFilterOverlay(CommandContext<CommandSourceStack> ctx, Map<String, String> p) {
        String filter = p.get("filter");
        if (filter != null) {
            if ("none".equalsIgnoreCase(filter)) FilterRegistry.get().select("");
            else FilterRegistry.get().select(filter);
            info(ctx, "滤镜：" + (filter.equalsIgnoreCase("none") ? "无" : filter));
        }
        String overlay = p.get("overlay");
        if (overlay != null) {
            if ("none".equalsIgnoreCase(overlay) || "clear".equalsIgnoreCase(overlay)) {
                OverlayRegistry.get().clear(); info(ctx, "叠加已清空");
            } else {
                var model = ParamParser.parseOverlay(overlay);
                if (model != null && OverlayRegistry.get().show(model)) {
                    info(ctx, "叠加：" + model.image());
                }
            }
        }
    }

    // ===== camera CRUD（无参数变化的保持原有） =====

    private static int cameraList(CommandContext<CommandSourceStack> ctx) {
        var cameras = CameraManager.get().list();
        int active = CameraManager.get().activeCameraId();
        if (cameras.isEmpty()) { info(ctx, "无相机"); return 1; }
        info(ctx, "相机（active=" + active + "，共 " + cameras.size() + " 台）：");
        for (CameraSpec s : cameras) {
            info(ctx, "  #" + s.id() + " (" + f2(s.positionX()) + "," + f2(s.positionY()) + "," + f2(s.positionZ())
                    + ") fov=" + f2(s.fovDegrees()) + " " + s.width() + "x" + s.height()
                    + (s.id() == active ? " §a←active§r" : "") + (s.paused() ? " §e(暂停)§r" : ""));
        }
        return 1;
    }

    private static int cameraStatus(CommandContext<CommandSourceStack> ctx) {
        CameraSpec a = CameraManager.get().active();
        if (a == null) { info(ctx, "无 active 相机"); return 1; }
        info(ctx, "active #" + a.id() + " (" + f2(a.positionX()) + "," + f2(a.positionY()) + "," + f2(a.positionZ())
                + ") rot=" + f2(a.yawDegrees()) + "," + f2(a.pitchDegrees()) + "," + f2(a.rollDegrees())
                + " fov=" + f2(a.fovDegrees()) + " " + a.width() + "x" + a.height()
                + (a.paused() ? " §e[暂停]§r" : ""));
        info(ctx, "滤镜=" + FilterRegistry.get().selected() + " 叠加层数=" + OverlayRegistry.get().layers().size());
        return 1;
    }

    private static int cameraActivate(CommandContext<CommandSourceStack> ctx) {
        int id = IntegerArgumentType.getInteger(ctx, "id");
        try { CameraManager.get().activate(id); ok(ctx, "已激活 #" + id); return 1; }
        catch (IllegalArgumentException e) { err(ctx, "相机不存在：" + id); return 0; }
    }

    private static int cameraPauseResume(CommandContext<CommandSourceStack> ctx, boolean pause) {
        int id = IntegerArgumentType.getInteger(ctx, "id");
        try {
            if (pause) CameraManager.get().pause(id); else CameraManager.get().resume(id);
            ok(ctx, (pause ? "已暂停" : "已恢复") + " #" + id); return 1;
        } catch (IllegalArgumentException e) { err(ctx, "相机不存在：" + id); return 0; }
    }

    private static int cameraDelete(CommandContext<CommandSourceStack> ctx) {
        int id = IntegerArgumentType.getInteger(ctx, "id");
        if (CameraManager.get().get(id) == null) { err(ctx, "相机不存在：" + id); return 0; }
        CameraManager.get().delete(id);
        ok(ctx, "已删除 #" + id + "（active=" + CameraManager.get().activeCameraId() + "）");
        return 1;
    }

    // =====================================================================
    // record —— 录像
    // =====================================================================

    private static LiteralArgumentBuilder<CommandSourceStack> recordTree() {
        return Commands.literal("record")
                .then(Commands.literal("start")
                        .executes(ctx -> doRecordStart(ctx, ""))
                        .then(Commands.argument("params", StringArgumentType.greedyString())
                                .executes(ctx -> doRecordStart(ctx, StringArgumentType.getString(ctx, "params")))))
                .then(Commands.literal("stop").executes(CaptureCommand::recordStop))
                .then(Commands.literal("status").executes(CaptureCommand::recordStatus));
    }

    private static int doRecordStart(CommandContext<CommandSourceStack> ctx, String paramString) {
        Map<String, String> p = ParamParser.parse(paramString);
        int fps = (int) ParamParser.getDouble(p, "fps", 30, 1, 240);
        boolean audio = "true".equalsIgnoreCase(p.getOrDefault("audio", "false"));
        String out = p.get("out");
        try {
            RecordingParams params = new RecordingParams(0, fps, 0, audio, out);
            RecordingStatus st = RecordingSession.get().start(params);
            ok(ctx, "录像已启动 → " + st.outputPath() + "（音频=" + st.audioAvailable() + "）");
            return 1;
        } catch (Exception e) { err(ctx, e.getMessage()); return 0; }
    }

    private static int recordStop(CommandContext<CommandSourceStack> ctx) {
        RecordingStatus st = RecordingSession.get().stop();
        ok(ctx, "录像已停止（帧数=" + st.frames() + "）");
        return 1;
    }

    private static int recordStatus(CommandContext<CommandSourceStack> ctx) {
        RecordingStatus st = RecordingSession.get().status();
        info(ctx, "录像：" + (st.running() ? "§a进行中§r" : "已停止")
                + " 帧数=" + st.frames() + " 音频=" + st.audioAvailable()
                + (st.error() != null ? " §c" + st.error() + "§r" : ""));
        return 1;
    }

    // =====================================================================
    // 管理（list/reload）+ 信息 + 参数写入 + 帮助
    // =====================================================================

    private static int filterList(CommandContext<CommandSourceStack> ctx) {
        info(ctx, "滤镜（当前=" + FilterRegistry.get().selected() + "）：" + String.join(", ", FilterRegistry.get().names()));
        return 1;
    }

    private static int filterReload(CommandContext<CommandSourceStack> ctx) {
        FilterRegistry.get().reload();
        ok(ctx, "滤镜已重载：" + String.join(", ", FilterRegistry.get().names()));
        return 1;
    }

    private static int overlayList(CommandContext<CommandSourceStack> ctx) {
        info(ctx, "叠加图：" + String.join(", ", OverlayRegistry.get().images()));
        info(ctx, "当前图层：" + OverlayRegistry.get().layers().size() + " 层");
        return 1;
    }

    private static int overlayReload(CommandContext<CommandSourceStack> ctx) {
        OverlayRegistry.get().reload();
        ok(ctx, "叠加已重扫：" + String.join(", ", OverlayRegistry.get().images()));
        return 1;
    }

    private static int renderFilterList(CommandContext<CommandSourceStack> ctx) {
        info(ctx, "渲染名单：" + RenderFilterRegistry.get().summary()); return 1;
    }

    private static int renderFilterReload(CommandContext<CommandSourceStack> ctx) {
        ok(ctx, "渲染名单已重载：" + RenderFilterRegistry.get().reload()); return 1;
    }

    private static int audioFilterList(CommandContext<CommandSourceStack> ctx) {
        info(ctx, "音频名单：" + AudioFilterRegistry.get().summary()); return 1;
    }

    private static int audioFilterReload(CommandContext<CommandSourceStack> ctx) {
        ok(ctx, "音频名单已重载：" + AudioFilterRegistry.get().reload()); return 1;
    }

    private static int health(CommandContext<CommandSourceStack> ctx) {
        var mgr = NetworkServiceManager.get();
        info(ctx, "§6=== CaptureAPI ===§r");
        info(ctx, "服务=" + (mgr.isRunning() ? "§a运行中§r" : "§c停止§r")
                + " HTTP=" + mgr.httpPort() + " WS=" + mgr.wsPort()
                + " 连接=" + mgr.connectionCount());
        info(ctx, "音频环回=" + (com.captureapi.audio.AudioCaptureManager.get().isLoopbackActive() ? "§a激活§r" : "§c未激活§r"));
        info(ctx, "视频渲染=" + com.captureapi.video.render.OffscreenRenderPipeline.renderedFrames()
                + " 失败=" + com.captureapi.video.render.OffscreenRenderPipeline.failedFrames());
        return 1;
    }

    private static int frameInfo(CommandContext<CommandSourceStack> ctx) {
        var frame = com.captureapi.video.render.OffscreenRenderPipeline.latestFrame();
        if (frame == null) { info(ctx, "暂无帧（需进入世界且有 active 相机）"); return 1; }
        info(ctx, "最新帧：" + frame.width() + "x" + frame.height()
                + " ts=" + frame.timestampNanos() + " " + frame.rgba().length + "B");
        return 1;
    }

    private static int shaderStatus(CommandContext<CommandSourceStack> ctx) {
        info(ctx, "光影：backend=" + com.captureapi.video.shader.ShaderCompatHooks.backend()
                + " detected=" + com.captureapi.video.shader.ShaderCompatHooks.detected()
                + " active=" + com.captureapi.video.shader.ShaderCompatHooks.isActive());
        return 1;
    }

    private static int configGetAll(CommandContext<CommandSourceStack> ctx) {
        var all = RuntimeConfigWriter.getAll();
        info(ctx, "全局参数（" + all.size() + " 项，capture 可覆盖）：");
        for (var e : all.entrySet()) info(ctx, "  " + e.getKey() + " = " + e.getValue());
        return 1;
    }

    private static int configGetOne(CommandContext<CommandSourceStack> ctx) {
        String key = StringArgumentType.getString(ctx, "key");
        Object v = RuntimeConfigWriter.get(key).orElse(null);
        if (v == null) { err(ctx, "未知参数：" + key); return 0; }
        ok(ctx, key + " = " + v); return 1;
    }

    private static int configSetOne(CommandContext<CommandSourceStack> ctx) {
        String key = StringArgumentType.getString(ctx, "key");
        String raw = StringArgumentType.getString(ctx, "value").trim();
        com.google.gson.JsonElement jsonValue;
        if ("true".equalsIgnoreCase(raw) || "false".equalsIgnoreCase(raw)) jsonValue = new com.google.gson.JsonPrimitive(Boolean.parseBoolean(raw));
        else {
            try { jsonValue = new com.google.gson.JsonPrimitive(Integer.parseInt(raw)); }
            catch (NumberFormatException e) {
                try { jsonValue = new com.google.gson.JsonPrimitive(Double.parseDouble(raw)); }
                catch (NumberFormatException e2) { jsonValue = new com.google.gson.JsonPrimitive(raw); }
            }
        }
        RuntimeConfigWriter.Result r = RuntimeConfigWriter.set(key, jsonValue);
        if (r.applied()) { ok(ctx, key + " = " + r.value() + (r.persisted() ? "（已持久化）" : "（临时）")); return 1; }
        err(ctx, r.message()); return 0;
    }

    private static int audioSection(CommandContext<CommandSourceStack> ctx) {
        try {
            double x = com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(ctx, "x");
            double y = com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(ctx, "y");
            double z = com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(ctx, "z");
            var chunk = com.captureapi.net.CaptureApi.grabAudioSection(x, y, z, 1)
                    .get(5, java.util.concurrent.TimeUnit.SECONDS);
            if (chunk != null) ok(ctx, "音频截面：" + chunk.sampleCount() + "采样 " + chunk.pcm().length + "B");
            else err(ctx, "音频截面返回空");
            return 1;
        } catch (Exception e) { err(ctx, e.getMessage()); return 0; }
    }

    // =====================================================================
    // help
    // =====================================================================

    private static int help(CommandContext<CommandSourceStack> ctx) {
        info(ctx, "§6=== /captureapi v2（函数式参数） ===§r");
        info(ctx, "§e核心操作§r：");
        info(ctx, "  §b/captureapi capture§r [参数] — 一次性截取（全部参数可选）");
        info(ctx, "  §b/captureapi camera create§r [参数] — 创建相机");
        info(ctx, "  §b/captureapi camera adjust§r <id> [参数] — 调整相机");
        info(ctx, "  §b/captureapi record start§r [参数] — 开始录像");
        info(ctx, "§e可选参数§r（key:value 空格分隔）：");
        info(ctx, "  §7pos:§fx,y,z§7  §7rot:§fyaw,pitch[,roll]§7  §7fov:§f度数");
        info(ctx, "  §7filter:§f名称§7  §7overlay:§f名称[@x,y,sx,sy,rot,opacity]");
        info(ctx, "  §7quality:§f0-100§7  §7res:§f宽x高§7  §7audio:§f秒数§7  §7out:§f路径");
        info(ctx, "  §7bound:§fUUID[@ox,oy,oz]§7  §7fps:§f帧率§7  §7shape:§frect|sphere");
        info(ctx, "§e示例§r：");
        info(ctx, "  /captureapi capture");
        info(ctx, "  /captureapi capture filter:grayscale quality:95");
        info(ctx, "  /captureapi capture pos:100,64,200 rot:45,-10 overlay:watermark@0.9,0.1,0.3,1,0,0.8");
        info(ctx, "  /captureapi camera create pos:100,64,200 fov:90 filter:grayscale");
        info(ctx, "§e其他§r：camera list/status/activate/pause/resume/delete");
        info(ctx, "  record stop/status · filter/overlay list/reload · info health/frame/shader/config · set · help");
        return 1;
    }

    // =====================================================================
    // 参数解析器
    // =====================================================================

    static final class ParamParser {

        private ParamParser() {
        }

        /** 解析空格分隔的 key:value 对 */
        static Map<String, String> parse(String input) {
            Map<String, String> map = new HashMap<>();
            if (input == null || input.isBlank()) return map;
            for (String token : input.trim().split("\\s+")) {
                int idx = token.indexOf(':');
                if (idx > 0 && idx < token.length() - 1) {
                    map.put(token.substring(0, idx).toLowerCase(), token.substring(idx + 1));
                }
            }
            return map;
        }

        /** 取 double 参数（带默认值和范围校验） */
        static double getDouble(Map<String, String> p, String key, double def, double min, double max) {
            String v = p.get(key);
            if (v == null) return def;
            try {
                double d = Double.parseDouble(v.trim());
                return Math.max(min, Math.min(max, d));
            } catch (NumberFormatException e) { return def; }
        }

        /** 取三维向量参数（逗号分隔，可省略部分维度） */
        static double[] getVec3(Map<String, String> p, String key, double defX, double defY, double defZ) {
            String v = p.get(key);
            if (v == null) return new double[]{defX, defY, defZ};
            String[] parts = v.split(",");
            double x = parts.length > 0 ? parseD(parts[0], defX) : defX;
            double y = parts.length > 1 ? parseD(parts[1], defY) : defY;
            double z = parts.length > 2 ? parseD(parts[2], defZ) : defZ;
            return new double[]{x, y, z};
        }

        /**
         * 解析叠加参数。
         * 格式：名称 或 名称@x,y,scaleX,scaleY,rotation,opacity
         * @x,y 为归一化位置 (0~1)，@scaleX,scaleY 为缩放 (>0)，@rotation 为度，@opacity 为 0~1
         */
        static OverlayModel parseOverlay(String spec) {
            if (spec == null || spec.isBlank()) return null;
            String name = spec;
            double x = 0.5, y = 0.5, sx = 1, sy = 1, rot = 0, op = 1;
            int at = spec.indexOf('@');
            if (at > 0) {
                name = spec.substring(0, at);
                String[] parts = spec.substring(at + 1).split(",");
                if (parts.length > 0) x = parseD(parts[0], 0.5);
                if (parts.length > 1) y = parseD(parts[1], 0.5);
                if (parts.length > 2) sx = parseD(parts[2], 1);
                if (parts.length > 3) sy = parseD(parts[3], sx);
                if (parts.length > 4) rot = parseD(parts[4], 0);
                if (parts.length > 5) op = parseD(parts[5], 1);
            }
            return new OverlayModel(name.trim(), x, y, sx, sy, rot, op);
        }

        private static double parseD(String s, double def) {
            try { return Double.parseDouble(s.trim()); } catch (Exception e) { return def; }
        }
    }

    // ===== 辅助 =====

    private static void ok(CommandContext<CommandSourceStack> ctx, String msg) {
        ctx.getSource().sendSuccess(() -> Component.literal("§a[CaptureAPI]§r " + msg), false);
    }

    private static void err(CommandContext<CommandSourceStack> ctx, String msg) {
        ctx.getSource().sendFailure(Component.literal("§c[CaptureAPI]§r " + msg));
    }

    private static void info(CommandContext<CommandSourceStack> ctx, String msg) {
        ctx.getSource().sendSuccess(() -> Component.literal("§b[CaptureAPI]§r " + msg), false);
    }

    private static String f2(double v) { return String.format("%.1f", v); }
}
