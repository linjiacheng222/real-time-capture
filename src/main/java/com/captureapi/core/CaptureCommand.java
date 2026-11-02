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
import com.mojang.brigadier.arguments.DoubleArgumentType;
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

/**
 * 游戏内 /captureapi 指令集——覆盖全部操作域（相机/参数/滤镜/叠加/名单/录像/信息）。
 *
 * <p>注册于 Forge 事件总线（RegisterCommandsEvent），单机/局域网下可用（集成服务器处理）。
 * 逻辑与 WS ControlCommandDispatcher 同源（调同一 Manager/Registry），不重复实现。</p>
 */
@Mod.EventBusSubscriber(modid = CaptureInterface.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CaptureCommand {

    private static final Logger LOGGER = LoggerFactory.getLogger(CaptureConstants.LOG_MARKER);

    private CaptureCommand() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(build());
        LOGGER.info("{} [CMD] /captureapi 指令集已注册", CaptureConstants.LOG_MARKER);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("captureapi")
                .then(cameraTree())
                .then(configTree())
                .then(filterTree())
                .then(overlayTree())
                .then(renderFilterTree())
                .then(audioFilterTree())
                .then(recordingTree())
                .then(audioTree())
                .then(shaderTree())
                .then(Commands.literal("health").executes(CaptureCommand::health))
                .then(Commands.literal("frame").executes(CaptureCommand::frameInfo))
                .then(Commands.literal("help").executes(CaptureCommand::help));
    }

    // ===== 相机 =====

    private static LiteralArgumentBuilder<CommandSourceStack> cameraTree() {
        var fovArg = Commands.argument("fov", DoubleArgumentType.doubleArg())
                .executes(ctx -> createCamera(ctx, d(ctx,"x"), d(ctx,"y"), d(ctx,"z"), d(ctx,"yaw"), d(ctx,"pitch"), d(ctx,"fov"), true));
        var pitchArg = Commands.argument("pitch", DoubleArgumentType.doubleArg())
                .executes(ctx -> createCamera(ctx, d(ctx,"x"), d(ctx,"y"), d(ctx,"z"), d(ctx,"yaw"), d(ctx,"pitch"), 70, true))
                .then(fovArg);
        var yawArg = Commands.argument("yaw", DoubleArgumentType.doubleArg()).then(pitchArg);
        var zArg = Commands.argument("z", DoubleArgumentType.doubleArg())
                .executes(ctx -> createCamera(ctx, d(ctx,"x"), d(ctx,"y"), d(ctx,"z"), 0, 0, 70, true))
                .then(yawArg);
        var yArg = Commands.argument("y", DoubleArgumentType.doubleArg()).then(zArg);
        var xArg = Commands.argument("x", DoubleArgumentType.doubleArg()).then(yArg);
        var createCmd = Commands.literal("create")
                .executes(ctx -> createCamera(ctx, 0, 0, 0, 0, 0, 70, false))
                .then(xArg);

        var adjustZ = Commands.argument("z", DoubleArgumentType.doubleArg())
                .executes(CaptureCommand::cameraAdjust)
                .then(Commands.argument("yaw", DoubleArgumentType.doubleArg())
                        .executes(CaptureCommand::cameraAdjust)
                        .then(Commands.argument("pitch", DoubleArgumentType.doubleArg())
                                .executes(CaptureCommand::cameraAdjust)));
        var adjustY = Commands.argument("y", DoubleArgumentType.doubleArg()).then(adjustZ);
        var adjustX = Commands.argument("x", DoubleArgumentType.doubleArg()).then(adjustY);
        var adjustCmd = Commands.literal("adjust")
                .then(Commands.argument("id", IntegerArgumentType.integer(1)).then(adjustX));

        return Commands.literal("camera")
                .then(createCmd)
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
                                .executes(CaptureCommand::cameraDelete)))
                .then(adjustCmd);
    }

    private static double d(CommandContext<CommandSourceStack> ctx, String name) {
        return DoubleArgumentType.getDouble(ctx, name);
    }

    private static int createCamera(CommandContext<CommandSourceStack> ctx,
            double x, double y, double z, double yaw, double pitch, double fov, boolean hasExplicit) {
        try {
            var source = ctx.getSource();
            if (!hasExplicit && source.getPlayer() != null) {
                var pos = source.getPlayer().position();
                x = pos.x; y = pos.y; z = pos.z;
                yaw = source.getPlayer().getYRot();
                pitch = source.getPlayer().getXRot();
            }
            if (fov <= 1 || fov >= 179) fov = 70;
            CameraCreateParams params = CameraCreateParams.builder()
                    .position(x, y, z).rotation((float) yaw, (float) pitch, 0).fov(fov).build();
            CameraSpec spec = CameraManager.get().create(params);
            ok(ctx, "相机创建 id=" + spec.id() + " pos=(" + fmt(x) + "," + fmt(y) + "," + fmt(z)
                    + ") yaw=" + fmt(yaw) + " pitch=" + fmt(pitch) + " fov=" + fmt(fov));
            return 1;
        } catch (Exception e) {
            err(ctx, e.getMessage());
            return 0;
        }
    }

    private static int cameraList(CommandContext<CommandSourceStack> ctx) {
        var cameras = CameraManager.get().list();
        int active = CameraManager.get().activeCameraId();
        if (cameras.isEmpty()) { info(ctx, "无相机（active=" + active + "）"); return 1; }
        info(ctx, "相机列表（active=" + active + "，共 " + cameras.size() + " 台）：");
        for (CameraSpec s : cameras) {
            String marker = s.id() == active ? " §a←active§r" : (s.paused() ? " §e(暂停)§r" : "");
            info(ctx, "  #" + s.id() + " (" + fmt(s.positionX()) + "," + fmt(s.positionY()) + "," + fmt(s.positionZ())
                    + ") yaw=" + fmt(s.yawDegrees()) + " fov=" + fmt(s.fovDegrees())
                    + " " + s.width() + "x" + s.height() + marker);
        }
        return 1;
    }

    private static int cameraStatus(CommandContext<CommandSourceStack> ctx) {
        CameraSpec a = CameraManager.get().active();
        if (a == null) { info(ctx, "无 active 相机"); return 1; }
        info(ctx, "active #" + a.id() + " (" + fmt(a.positionX()) + "," + fmt(a.positionY()) + "," + fmt(a.positionZ())
                + ") yaw=" + fmt(a.yawDegrees()) + " pitch=" + fmt(a.pitchDegrees())
                + " fov=" + fmt(a.fovDegrees()) + " " + a.width() + "x" + a.height()
                + (a.paused() ? " §e[暂停]§r" : ""));
        return 1;
    }

    private static int cameraActivate(CommandContext<CommandSourceStack> ctx) {
        int id = IntegerArgumentType.getInteger(ctx, "id");
        try { CameraManager.get().activate(id); ok(ctx, "已激活相机 #" + id); return 1; }
        catch (IllegalArgumentException e) { err(ctx, "相机不存在：" + id); return 0; }
    }

    private static int cameraPauseResume(CommandContext<CommandSourceStack> ctx, boolean pause) {
        int id = IntegerArgumentType.getInteger(ctx, "id");
        try {
            if (pause) CameraManager.get().pause(id); else CameraManager.get().resume(id);
            ok(ctx, (pause ? "已暂停" : "已恢复") + "相机 #" + id); return 1;
        } catch (IllegalArgumentException e) { err(ctx, "相机不存在：" + id); return 0; }
    }

    private static int cameraDelete(CommandContext<CommandSourceStack> ctx) {
        int id = IntegerArgumentType.getInteger(ctx, "id");
        if (CameraManager.get().get(id) == null) { err(ctx, "相机不存在：" + id); return 0; }
        CameraManager.get().delete(id);
        ok(ctx, "已删除相机 #" + id + "（active=" + CameraManager.get().activeCameraId() + "）");
        return 1;
    }

    private static int cameraAdjust(CommandContext<CommandSourceStack> ctx) {
        int id = IntegerArgumentType.getInteger(ctx, "id");
        CameraSpec old = CameraManager.get().get(id);
        if (old == null) { err(ctx, "相机不存在：" + id); return 0; }
        double x = d(ctx, "x"), y = d(ctx, "y"), z = d(ctx, "z");
        float yaw = old.yawDegrees();
        float pitch = old.pitchDegrees();
        try { if (ctx.getArgument("yaw", double.class) != null) yaw = (float) d(ctx, "yaw"); } catch (Exception ignored) { }
        try { if (ctx.getArgument("pitch", double.class) != null) pitch = (float) d(ctx, "pitch"); } catch (Exception ignored) { }
        CameraManager.get().adjust(id, CameraCreateParams.builder()
                .position(x, y, z).rotation(yaw, pitch, old.rollDegrees()).fov(old.fovDegrees()).build());
        ok(ctx, "相机 #" + id + " 已调整 → (" + fmt(x) + "," + fmt(y) + "," + fmt(z) + ")");
        return 1;
    }

    // ===== 参数 =====

    private static LiteralArgumentBuilder<CommandSourceStack> configTree() {
        return Commands.literal("config")
                .then(Commands.literal("get")
                        .executes(CaptureCommand::configGetAll)
                        .then(Commands.argument("key", StringArgumentType.string())
                                .executes(CaptureCommand::configGetOne)))
                .then(Commands.literal("set")
                        .then(Commands.argument("key", StringArgumentType.string())
                                .then(Commands.argument("value", StringArgumentType.string())
                                        .executes(CaptureCommand::configSetOne))));
    }

    private static int configGetAll(CommandContext<CommandSourceStack> ctx) {
        var all = RuntimeConfigWriter.getAll();
        info(ctx, "全部参数（" + all.size() + " 项）：");
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
        String raw = StringArgumentType.getString(ctx, "value");
        Object parsed;
        if ("true".equalsIgnoreCase(raw) || "false".equalsIgnoreCase(raw)) parsed = Boolean.parseBoolean(raw);
        else {
            try { parsed = Integer.parseInt(raw); }
            catch (NumberFormatException e) {
                try { parsed = Double.parseDouble(raw); }
                catch (NumberFormatException e2) { parsed = raw; }
            }
        }
        com.google.gson.JsonElement jsonValue;
        if (parsed instanceof Boolean b) jsonValue = new com.google.gson.JsonPrimitive(b);
        else if (parsed instanceof Number n) jsonValue = new com.google.gson.JsonPrimitive(n);
        else jsonValue = new com.google.gson.JsonPrimitive(String.valueOf(parsed));
        RuntimeConfigWriter.Result r = RuntimeConfigWriter.set(key, jsonValue);
        if (r.applied()) { ok(ctx, key + " = " + r.value() + (r.persisted() ? "（已持久化）" : "（临时）")); return 1; }
        err(ctx, r.message()); return 0;
    }

    // ===== 滤镜 =====

    private static LiteralArgumentBuilder<CommandSourceStack> filterTree() {
        return Commands.literal("filter")
                .then(Commands.literal("list").executes(CaptureCommand::filterList))
                .then(Commands.literal("set")
                        .then(Commands.argument("name", StringArgumentType.string())
                                .executes(CaptureCommand::filterSet)))
                .then(Commands.literal("reload").executes(CaptureCommand::filterReload));
    }

    private static int filterList(CommandContext<CommandSourceStack> ctx) {
        info(ctx, "滤镜（selected=" + FilterRegistry.get().selected() + "）："
                + String.join(", ", FilterRegistry.get().names()));
        return 1;
    }

    private static int filterSet(CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "name");
        if (FilterRegistry.get().select(name)) { ok(ctx, "滤镜已选：" + name); return 1; }
        err(ctx, FilterRegistry.get().lastError()); return 0;
    }

    private static int filterReload(CommandContext<CommandSourceStack> ctx) {
        FilterRegistry.get().reload();
        ok(ctx, "滤镜已重载：" + String.join(", ", FilterRegistry.get().names()));
        return 1;
    }

    // ===== 叠加 =====

    private static LiteralArgumentBuilder<CommandSourceStack> overlayTree() {
        return Commands.literal("overlay")
                .then(Commands.literal("list").executes(CaptureCommand::overlayList))
                .then(Commands.literal("clear").executes(CaptureCommand::overlayClear))
                .then(Commands.literal("reload").executes(CaptureCommand::overlayReload))
                .then(Commands.literal("show")
                        .then(Commands.argument("image", StringArgumentType.string())
                                .executes(CaptureCommand::overlayShowDefault)
                                .then(Commands.argument("x", DoubleArgumentType.doubleArg(0, 1))
                                        .then(Commands.argument("y", DoubleArgumentType.doubleArg(0, 1))
                                                .executes(CaptureCommand::overlayShowXY)))));
    }

    private static int overlayList(CommandContext<CommandSourceStack> ctx) {
        info(ctx, "叠加图：" + String.join(", ", OverlayRegistry.get().images()));
        return 1;
    }

    private static int overlayShowDefault(CommandContext<CommandSourceStack> ctx) {
        return overlayShow(ctx, StringArgumentType.getString(ctx, "image"), 0.5, 0.5);
    }

    private static int overlayShowXY(CommandContext<CommandSourceStack> ctx) {
        return overlayShow(ctx, StringArgumentType.getString(ctx, "image"), d(ctx, "x"), d(ctx, "y"));
    }

    private static int overlayShow(CommandContext<CommandSourceStack> ctx, String image, double x, double y) {
        OverlayModel model = new OverlayModel(image, x, y, 1.0, 1.0, 0.0, 1.0);
        if (OverlayRegistry.get().show(model)) {
            ok(ctx, "叠加已添加：" + image + " @(" + fmt(x) + "," + fmt(y) + ")");
            return 1;
        }
        err(ctx, "叠加图不存在：" + image); return 0;
    }

    private static int overlayClear(CommandContext<CommandSourceStack> ctx) {
        OverlayRegistry.get().clear(); ok(ctx, "叠加图层已清空"); return 1;
    }

    private static int overlayReload(CommandContext<CommandSourceStack> ctx) {
        OverlayRegistry.get().reload();
        ok(ctx, "叠加已重扫：" + String.join(", ", OverlayRegistry.get().images()));
        return 1;
    }

    // ===== 名单 =====

    private static LiteralArgumentBuilder<CommandSourceStack> renderFilterTree() {
        return Commands.literal("renderfilter")
                .then(Commands.literal("list").executes(CaptureCommand::renderFilterList))
                .then(Commands.literal("reload").executes(CaptureCommand::renderFilterReload));
    }

    private static int renderFilterList(CommandContext<CommandSourceStack> ctx) {
        info(ctx, "渲染名单：" + RenderFilterRegistry.get().summary()); return 1;
    }

    private static int renderFilterReload(CommandContext<CommandSourceStack> ctx) {
        String msg = RenderFilterRegistry.get().reload();
        ok(ctx, "渲染名单已重载：" + msg); return 1;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> audioFilterTree() {
        return Commands.literal("audiofilter")
                .then(Commands.literal("list").executes(CaptureCommand::audioFilterList))
                .then(Commands.literal("reload").executes(CaptureCommand::audioFilterReload));
    }

    private static int audioFilterList(CommandContext<CommandSourceStack> ctx) {
        info(ctx, "音频名单：" + AudioFilterRegistry.get().summary()); return 1;
    }

    private static int audioFilterReload(CommandContext<CommandSourceStack> ctx) {
        String msg = AudioFilterRegistry.get().reload();
        ok(ctx, "音频名单已重载：" + msg); return 1;
    }

    // ===== 录像 =====

    private static LiteralArgumentBuilder<CommandSourceStack> recordingTree() {
        return Commands.literal("recording")
                .then(Commands.literal("start")
                        .executes(CaptureCommand::recordingStartDefault)
                        .then(Commands.argument("fps", IntegerArgumentType.integer(1, 240))
                                .executes(CaptureCommand::recordingStartFps)))
                .then(Commands.literal("stop").executes(CaptureCommand::recordingStop))
                .then(Commands.literal("status").executes(CaptureCommand::recordingStatus));
    }

    private static int recordingStartDefault(CommandContext<CommandSourceStack> ctx) {
        return recordingStart(ctx, 0);
    }

    private static int recordingStartFps(CommandContext<CommandSourceStack> ctx) {
        return recordingStart(ctx, IntegerArgumentType.getInteger(ctx, "fps"));
    }

    private static int recordingStart(CommandContext<CommandSourceStack> ctx, int fps) {
        try {
            RecordingParams params = new RecordingParams(0, fps, 0, false, null);
            RecordingStatus st = RecordingSession.get().start(params);
            ok(ctx, "录像已启动 → " + st.outputPath()); return 1;
        } catch (Exception e) { err(ctx, e.getMessage()); return 0; }
    }

    private static int recordingStop(CommandContext<CommandSourceStack> ctx) {
        RecordingStatus st = RecordingSession.get().stop();
        ok(ctx, "录像已停止（帧数=" + st.frames() + "）"); return 1;
    }

    private static int recordingStatus(CommandContext<CommandSourceStack> ctx) {
        RecordingStatus st = RecordingSession.get().status();
        info(ctx, "录像：" + (st.running() ? "§a进行中§r" : "已停止")
                + " 帧数=" + st.frames() + " 音频=" + st.audioAvailable()
                + (st.error() != null ? " §c错误=" + st.error() + "§r" : ""));
        return 1;
    }

    // ===== 音频截面 / 光影 / 健康 / 帧 / 帮助 =====

    private static LiteralArgumentBuilder<CommandSourceStack> audioTree() {
        return Commands.literal("audio")
                .then(Commands.literal("section")
                        .then(Commands.argument("x", DoubleArgumentType.doubleArg())
                                .then(Commands.argument("y", DoubleArgumentType.doubleArg())
                                        .then(Commands.argument("z", DoubleArgumentType.doubleArg())
                                                .executes(CaptureCommand::audioSection)))));
    }

    private static int audioSection(CommandContext<CommandSourceStack> ctx) {
        try {
            var chunk = com.captureapi.net.CaptureApi.grabAudioSection(d(ctx,"x"), d(ctx,"y"), d(ctx,"z"), 1)
                    .get(5, java.util.concurrent.TimeUnit.SECONDS);
            if (chunk != null) {
                ok(ctx, "音频截面：ts=" + chunk.startNanos() + " " + chunk.sampleCount() + "采样 "
                        + chunk.pcm().length + "B");
            } else { err(ctx, "音频截面返回空"); }
            return 1;
        } catch (Exception e) { err(ctx, "音频截面失败：" + e.getMessage()); return 0; }
    }

    private static LiteralArgumentBuilder<CommandSourceStack> shaderTree() {
        return Commands.literal("shader")
                .then(Commands.literal("status").executes(CaptureCommand::shaderStatus));
    }

    private static int shaderStatus(CommandContext<CommandSourceStack> ctx) {
        info(ctx, "光影：backend=" + com.captureapi.video.shader.ShaderCompatHooks.backend()
                + " detected=" + com.captureapi.video.shader.ShaderCompatHooks.detected()
                + " active=" + com.captureapi.video.shader.ShaderCompatHooks.isActive());
        return 1;
    }

    private static int health(CommandContext<CommandSourceStack> ctx) {
        var mgr = NetworkServiceManager.get();
        info(ctx, "§6=== CaptureAPI 健康状态 ===§r");
        info(ctx, "运行=" + mgr.isRunning() + " HTTP=" + mgr.httpPort() + " WS=" + mgr.wsPort()
                + " 连接=" + mgr.connectionCount() + " 拒绝=" + mgr.rejectedConnections());
        info(ctx, "音频环回=" + com.captureapi.audio.AudioCaptureManager.get().isLoopbackActive());
        info(ctx, "视频渲染=" + com.captureapi.video.render.OffscreenRenderPipeline.renderedFrames()
                + " 失败=" + com.captureapi.video.render.OffscreenRenderPipeline.failedFrames()
                + " 回读=" + com.captureapi.video.render.OffscreenRenderPipeline.readbackFrames());
        return 1;
    }

    private static int frameInfo(CommandContext<CommandSourceStack> ctx) {
        var frame = com.captureapi.video.render.OffscreenRenderPipeline.latestFrame();
        if (frame == null) { info(ctx, "暂无帧（需进入世界且有 active 相机）"); return 1; }
        info(ctx, "最新帧：" + frame.width() + "x" + frame.height()
                + " ts=" + frame.timestampNanos() + " bytes=" + frame.rgba().length);
        return 1;
    }

    private static int help(CommandContext<CommandSourceStack> ctx) {
        info(ctx, "§6=== /captureapi 指令集 ===§r");
        info(ctx, "§ecamera§r create [x y z] [yaw pitch fov] — 创建（省略坐标＝玩家位置）");
        info(ctx, "§ecamera§r list/status/activate/pause/resume/delete/adjust");
        info(ctx, "§econfig§r get [key] / set <key> <value>");
        info(ctx, "§efilter§r list/set/reload");
        info(ctx, "§eoverlay§r list/show/clear/reload");
        info(ctx, "§erenderfilter§r / §eaudiofilter§r list/reload");
        info(ctx, "§erecording§r start [fps]/stop/status");
        info(ctx, "§eaudio§r section <x> <y> <z>");
        info(ctx, "§eshader§r status");
        info(ctx, "§ehealth§r / §eframe§r / §ehelp§r");
        return 1;
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

    private static String fmt(double v) { return String.format("%.1f", v); }
}
