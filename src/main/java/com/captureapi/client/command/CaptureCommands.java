package com.captureapi.client.command;

import com.captureapi.CaptureInterface;
import com.captureapi.audio.AudioCaptureManager;
import com.captureapi.audio.AudioFilterRegistry;
import com.captureapi.audio.AudioRingBuffer;
import com.captureapi.audio.timestamp.AudioChunk;
import com.captureapi.core.config.CaptureParams;
import com.captureapi.core.config.RuntimeConfigWriter;
import com.captureapi.core.constant.CaptureConstants;
import com.captureapi.core.time.CaptureClock;
import com.captureapi.net.CaptureApi;
import com.captureapi.net.FrameRequest;
import com.captureapi.net.RemoteCaptureClient;
import com.captureapi.net.recording.RecordingParams;
import com.captureapi.net.recording.RecordingSession;
import com.captureapi.net.recording.RecordingStatus;
import com.captureapi.video.camera.CameraCreateParams;
import com.captureapi.video.camera.CameraManager;
import com.captureapi.video.camera.CameraSpec;
import com.captureapi.video.filter.FilterRegistry;
import com.captureapi.video.filter.RenderFilterRegistry;
import com.captureapi.video.overlay.OverlayModel;
import com.captureapi.video.overlay.OverlayRegistry;
import com.captureapi.video.shader.ShaderCompatHooks;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 临时测试指令集（客户端聊天命令）。
 *
 * <p>根命令：{@code /captureapi}；另提供 {@code /get_image} = 玩家眼位上方 10 格俯视 PNG。
 * 所有落盘文件输出到 {@code E:\捕获接口\testcases\commands}。发布前应移除/门控本类。</p>
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = CaptureInterface.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CaptureCommands {

    private static final Logger LOGGER = LoggerFactory.getLogger(CaptureConstants.LOG_MARKER);
    private static final Path OUTPUT_DIR = Path.of("E:\\捕获接口\\testcases\\commands");
    private static final double DEFAULT_FOV = 70.0D;
    private static final double TOP_OFFSET = 10.0D;
    private static final long FRAME_TIMEOUT_MS = 5000L;
    private static final int DEFAULT_CHUNKS = 3;
    private static final int CONFIG_LIST_LIMIT = 40;
    private static final int DEFAULT_RECORD_FPS = 30;
    private static final int MAX_RECORD_FPS = 60;

    private CaptureCommands() {
        throw new AssertionError("no instances");
    }

    @SubscribeEvent
    public static void onRegister(RegisterClientCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(root());
        dispatcher.register(Commands.literal("get_image").executes(CaptureCommands::getImage));
        LOGGER.info("{} [CMD] 临时测试指令已注册：/captureapi ... 与 /get_image（输出 {}）",
                CaptureConstants.LOG_MARKER, OUTPUT_DIR);
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> root() {
        return Commands.literal("captureapi")
                .executes(CaptureCommands::help)
                .then(camera())
                .then(frame())
                .then(record())
                .then(audio())
                .then(config())
                .then(filter())
                .then(overlay())
                .then(diagnostics())
                .then(helpCmd())
                .then(captureAlias())
                .then(networkCmd())
                .then(listCmd())
                .then(unimplemented())
                .then(glslCmd())
                .then(renderListCmd())
                .then(audioListCmd())
                .then(multicamCmd())
                .then(overlayGpuCmd())
                .then(oculusCmd());
    }

    // ===== camera =====

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> camera() {
        return Commands.literal("camera")
                .executes(ctx -> help(ctx, "camera"))
                .then(Commands.literal("create")
                        .then(Commands.argument("x", DoubleArgumentType.doubleArg())
                                .then(Commands.argument("y", DoubleArgumentType.doubleArg())
                                        .then(Commands.argument("z", DoubleArgumentType.doubleArg())
                                                .then(Commands.argument("yaw", DoubleArgumentType.doubleArg())
                                                        .then(Commands.argument("pitch", DoubleArgumentType.doubleArg())
                                                                .executes(ctx -> createCamera(ctx, DEFAULT_FOV))
                                                                .then(Commands.argument("fov",
                                                                                DoubleArgumentType.doubleArg(1.1D, 178.9D))
                                                                        .executes(ctx -> createCamera(ctx,
                                                                                DoubleArgumentType.getDouble(ctx, "fov"))))))))))
                .then(Commands.literal("top")
                        .executes(ctx -> cameraTop(ctx, TOP_OFFSET))
                        .then(Commands.argument("height", DoubleArgumentType.doubleArg(1.0D, 64.0D))
                                .executes(ctx -> cameraTop(ctx, DoubleArgumentType.getDouble(ctx, "height")))))
                .then(Commands.literal("adjust")
                        .then(Commands.argument("id", IntegerArgumentType.integer(1))
                                .then(Commands.argument("x", DoubleArgumentType.doubleArg())
                                        .then(Commands.argument("y", DoubleArgumentType.doubleArg())
                                                .then(Commands.argument("z", DoubleArgumentType.doubleArg())
                                                        .then(Commands.argument("yaw", DoubleArgumentType.doubleArg())
                                                                .then(Commands.argument("pitch", DoubleArgumentType.doubleArg())
                                                                        .executes(ctx -> adjustCamera(ctx, DEFAULT_FOV))
                                                                        .then(Commands.argument("fov",
                                                                                        DoubleArgumentType.doubleArg(1.1D, 178.9D))
                                                                                .executes(ctx -> adjustCamera(ctx,
                                                                                        DoubleArgumentType.getDouble(ctx, "fov")))))))))))
                .then(Commands.literal("activate")
                        .then(Commands.argument("id", IntegerArgumentType.integer(1))
                                .executes(ctx -> cameraAction(ctx, "activate"))))
                .then(Commands.literal("pause")
                        .then(Commands.argument("id", IntegerArgumentType.integer(1))
                                .executes(ctx -> cameraAction(ctx, "pause"))))
                .then(Commands.literal("resume")
                        .then(Commands.argument("id", IntegerArgumentType.integer(1))
                                .executes(ctx -> cameraAction(ctx, "resume"))))
                .then(Commands.literal("delete")
                        .then(Commands.argument("id", IntegerArgumentType.integer(1))
                                .executes(ctx -> cameraAction(ctx, "delete"))))
                .then(Commands.literal("list").executes(ctx -> cameraList(ctx)))
                .then(Commands.literal("status").executes(ctx -> cameraStatus(ctx)))
                .then(Commands.literal("clear").executes(ctx -> {
                    CameraManager.get().clear();
                    return ok(ctx, "已清空全部相机");
                }));
    }

    private static int createCamera(CommandContext<CommandSourceStack> ctx, double fov) {
        try {
            CameraCreateParams params = CameraCreateParams.builder()
                    .position(DoubleArgumentType.getDouble(ctx, "x"),
                            DoubleArgumentType.getDouble(ctx, "y"),
                            DoubleArgumentType.getDouble(ctx, "z"))
                    .rotation((float) DoubleArgumentType.getDouble(ctx, "yaw"),
                            (float) DoubleArgumentType.getDouble(ctx, "pitch"), 0.0F)
                    .fov(fov)
                    .build();
            CameraSpec spec = CameraManager.get().create(params);
            CameraManager.get().activate(spec.id());
            return ok(ctx, "已创建并激活相机 " + CameraManager.describe(spec));
        } catch (Throwable t) {
            return fail(ctx, "创建相机失败：" + t.getMessage());
        }
    }

    private static int cameraTop(CommandContext<CommandSourceStack> ctx, double height) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return fail(ctx, "未进入世界");
        }
        try {
            CameraCreateParams params = CameraCreateParams.builder()
                    .position(mc.player.getX(), mc.player.getY() + height, mc.player.getZ())
                    .rotation(mc.player.getYRot(), 90.0F, 0.0F)
                    .fov(DEFAULT_FOV)
                    .build();
            CameraSpec spec = CameraManager.get().create(params);
            CameraManager.get().activate(spec.id());
            return ok(ctx, String.format("已创建俯视相机 id=%d（x=%.1f y=%.1f z=%.1f，pitch=90）",
                    spec.id(), spec.positionX(), spec.positionY(), spec.positionZ()));
        } catch (Throwable t) {
            return fail(ctx, "创建俯视相机失败：" + t.getMessage());
        }
    }

    private static int adjustCamera(CommandContext<CommandSourceStack> ctx, double fov) {
        int id = IntegerArgumentType.getInteger(ctx, "id");
        try {
            CameraCreateParams params = CameraCreateParams.builder()
                    .position(DoubleArgumentType.getDouble(ctx, "x"),
                            DoubleArgumentType.getDouble(ctx, "y"),
                            DoubleArgumentType.getDouble(ctx, "z"))
                    .rotation((float) DoubleArgumentType.getDouble(ctx, "yaw"),
                            (float) DoubleArgumentType.getDouble(ctx, "pitch"), 0.0F)
                    .fov(fov)
                    .build();
            return ok(ctx, "已调整相机 " + CameraManager.describe(CameraManager.get().adjust(id, params)));
        } catch (Throwable t) {
            return fail(ctx, "调整相机失败：" + t.getMessage());
        }
    }

    private static int cameraAction(CommandContext<CommandSourceStack> ctx, String action) {
        int id = IntegerArgumentType.getInteger(ctx, "id");
        try {
            switch (action) {
                case "activate" -> {
                    CameraManager.get().activate(id);
                    return ok(ctx, "已激活相机 id=" + id);
                }
                case "pause" -> {
                    CameraManager.get().pause(id);
                    return ok(ctx, "已暂停相机 id=" + id);
                }
                case "resume" -> {
                    CameraManager.get().resume(id);
                    return ok(ctx, "已恢复相机 id=" + id);
                }
                case "delete" -> {
                    CameraManager.get().delete(id);
                    return ok(ctx, "已删除相机 id=" + id);
                }
                default -> {
                    return fail(ctx, "未知相机动作：" + action);
                }
            }
        } catch (Throwable t) {
            return fail(ctx, action + " 失败：" + t.getMessage());
        }
    }

    private static int cameraList(CommandContext<CommandSourceStack> ctx) {
        List<CameraSpec> list = CameraManager.get().list();
        if (list.isEmpty()) {
            return ok(ctx, "当前没有相机");
        }
        for (CameraSpec spec : list) {
            int active = CameraManager.get().activeCameraId();
            String mark = spec.id() == active ? " [active]" : "";
            reply(ctx, "id=" + spec.id() + mark + " " + CameraManager.describe(spec), ChatFormatting.GRAY);
        }
        return 1;
    }

    private static int cameraStatus(CommandContext<CommandSourceStack> ctx) {
        CameraSpec active = CameraManager.get().active();
        if (active == null) {
            return ok(ctx, "当前无 active 相机；可用 /captureapi camera top 创建");
        }
        return ok(ctx, "active: " + CameraManager.describe(active) + "，总数=" + CameraManager.get().size());
    }

    // ===== frame =====

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> frame() {
        return Commands.literal("frame")
                .executes(ctx -> frameTop(ctx, "png"))
                .then(Commands.literal("top")
                        .executes(ctx -> frameTop(ctx, "png"))
                        .then(Commands.literal("png").executes(ctx -> frameTop(ctx, "png")))
                        .then(Commands.literal("jpeg").executes(ctx -> frameTop(ctx, "jpeg"))))
                .then(Commands.literal("current")
                        .executes(ctx -> frameCurrent(ctx, "png"))
                        .then(Commands.literal("png").executes(ctx -> frameCurrent(ctx, "png")))
                        .then(Commands.literal("jpeg").executes(ctx -> frameCurrent(ctx, "jpeg"))));
    }

    private static int getImage(CommandContext<CommandSourceStack> ctx) {
        return frameTop(ctx, "png");
    }

    private static int frameTop(CommandContext<CommandSourceStack> ctx, String format) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return fail(ctx, "未进入世界");
        }
        CommandSourceStack source = ctx.getSource();
        double x = mc.player.getX();
        double y = mc.player.getY() + TOP_OFFSET;
        double z = mc.player.getZ();
        float yaw = mc.player.getYRot();
        return captureAsync(source, "top", format, () -> FrameRequest.builder()
                .position(x, y, z)
                .rotation(yaw, 90.0F, 0.0F)
                .fov(DEFAULT_FOV)
                .temporary(true)
                .outputMethod(FrameRequest.OutputMethod.FILE)
                .format("png".equalsIgnoreCase(format) ? FrameRequest.Format.PNG : FrameRequest.Format.JPEG)
                .timeoutMillis(FRAME_TIMEOUT_MS)
                .build());
    }

    private static int frameCurrent(CommandContext<CommandSourceStack> ctx, String format) {
        CameraSpec active = CameraManager.get().active();
        if (active == null) {
            return fail(ctx, "当前无 active 相机；先用 camera top/create");
        }
        CommandSourceStack source = ctx.getSource();
        return captureAsync(source, "current", format, () -> FrameRequest.builder()
                .position(active.positionX(), active.positionY(), active.positionZ())
                .rotation(active.yawDegrees(), active.pitchDegrees(), active.rollDegrees())
                .fov(active.fovDegrees())
                .temporary(true)
                .outputMethod(FrameRequest.OutputMethod.FILE)
                .format("png".equalsIgnoreCase(format) ? FrameRequest.Format.PNG : FrameRequest.Format.JPEG)
                .timeoutMillis(FRAME_TIMEOUT_MS)
                .build());
    }

    private static int captureAsync(CommandSourceStack source, String kind, String format,
                                    java.util.function.Supplier<FrameRequest> requestSupplier) {
        try {
            Files.createDirectories(OUTPUT_DIR);
            String ext = "jpeg".equalsIgnoreCase(format) ? ".jpg" : ".png";
            Path output = OUTPUT_DIR.resolve("capture-" + kind + "-" + CaptureClock.get().nowNanos() + ext);
            reply(source, "开始截图→" + output, ChatFormatting.GRAY);
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                try {
                    FrameRequest request = FrameRequest.builder()
                            .outputMethod(FrameRequest.OutputMethod.FILE)
                            .format(requestSupplier.get().format())
                            .outputPath(output)
                            .position(requestSupplier.get().toCameraParams(null).x(),
                                    requestSupplier.get().toCameraParams(null).y(),
                                    requestSupplier.get().toCameraParams(null).z())
                            .rotation(requestSupplier.get().toCameraParams(null).yawDegrees(),
                                    requestSupplier.get().toCameraParams(null).pitchDegrees(), 0.0F)
                            .fov(requestSupplier.get().toCameraParams(null).fovDegrees())
                            .temporary(true)
                            .timeoutMillis(FRAME_TIMEOUT_MS)
                            .build();
                    CaptureApi.captureFrame(request);
                    replyMain(source, "截图完成：" + output + "（" + Files.size(output) + "B）", ChatFormatting.GREEN);
                } catch (Throwable t) {
                    LOGGER.warn("{} [CMD] 截图失败", CaptureConstants.LOG_MARKER, t);
                    replyMain(source, "截图失败：" + t.getMessage(), ChatFormatting.RED);
                }
            });
            return 1;
        } catch (Throwable t) {
            return fail(source, "截图启动失败：" + t.getMessage());
        }
    }

    // ===== record =====

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> record() {
        return Commands.literal("record")
                .executes(ctx -> help(ctx, "record"))
                .then(Commands.literal("start")
                        .executes(ctx -> recordStart(ctx, DEFAULT_RECORD_FPS, false))
                        .then(Commands.argument("fps", IntegerArgumentType.integer(1, MAX_RECORD_FPS))
                                .executes(ctx -> recordStart(ctx, IntegerArgumentType.getInteger(ctx, "fps"), false))
                                .then(Commands.literal("audio").executes(ctx -> recordStart(ctx,
                                        IntegerArgumentType.getInteger(ctx, "fps"), true)))
                                .then(Commands.literal("silent").executes(ctx -> recordStart(ctx,
                                        IntegerArgumentType.getInteger(ctx, "fps"), false))))
                        .then(Commands.literal("audio").executes(ctx -> recordStart(ctx, DEFAULT_RECORD_FPS, true)))
                        .then(Commands.literal("silent").executes(ctx -> recordStart(ctx, DEFAULT_RECORD_FPS, false))))
                .then(Commands.literal("stop").executes(ctx -> {
                    try {
                        RecordingStatus status = RecordingSession.get().stop();
                        return ok(ctx, "录像已停止：frames=" + status.frames() + "，输出=" + status.outputPath()
                                + "，音频=" + status.audioAvailable());
                    } catch (Throwable t) {
                        return fail(ctx, "停止录像失败：" + t.getMessage());
                    }
                }))
                .then(Commands.literal("status").executes(ctx -> {
                    RecordingStatus status = RecordingSession.get().status();
                    return ok(ctx, "running=" + status.running() + " frames=" + status.frames()
                            + " audio=" + status.audioAvailable() + " output=" + status.outputPath()
                            + " error=" + status.error());
                }));
    }

    private static int recordStart(CommandContext<CommandSourceStack> ctx, int fps, boolean includeAudio) {
        if (CameraManager.get().active() == null) {
            return fail(ctx, "无 active 相机；先 camera top/create");
        }
        try {
            Files.createDirectories(OUTPUT_DIR);
            String path = OUTPUT_DIR.resolve("recording-" + CaptureClock.get().nowNanos() + ".mp4").toString();
            RecordingStatus status = RecordingSession.get().start(new RecordingParams(
                    0, fps, CaptureParams.get().jpegQuality(), includeAudio, path));
            return ok(ctx, "录像开始：fps=" + fps + " 音频=" + includeAudio + "，输出=" + status.outputPath());
        } catch (Throwable t) {
            return fail(ctx, "开始录像失败：" + t.getMessage());
        }
    }

    // ===== audio =====

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> audio() {
        return Commands.literal("audio")
                .executes(ctx -> help(ctx, "audio"))
                .then(Commands.literal("status").executes(ctx -> {
                    return ok(ctx, "loopback=" + AudioCaptureManager.get().isLoopbackActive()
                            + " written=" + AudioRingBuffer.get().writtenPolls()
                            + " dropped=" + AudioRingBuffer.get().droppedPolls()
                            + " silent=" + AudioRingBuffer.get().silentPolls());
                }))
                .then(Commands.literal("section")
                        .executes(ctx -> audioSection(ctx, DEFAULT_CHUNKS))
                        .then(Commands.argument("chunks", IntegerArgumentType.integer(1, 100))
                                .executes(ctx -> audioSection(ctx, IntegerArgumentType.getInteger(ctx, "chunks")))));
    }

    private static int audioSection(CommandContext<CommandSourceStack> ctx, int chunks) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return fail(ctx, "未进入世界");
        }
        CommandSourceStack source = ctx.getSource();
        reply(source, "音频截面请求中（chunks=" + chunks + "）...", ChatFormatting.GRAY);
        CaptureApi.grabAudioSection(mc.player.getX(), mc.player.getEyeY(), mc.player.getZ(), chunks)
                .whenComplete((chunk, error) -> {
                    if (error != null) {
                        replyMain(source, "音频截面失败：" + error.getMessage(), ChatFormatting.RED);
                        return;
                    }
                    replyMain(source, "音频截面：samples=" + chunk.sampleCount() + " bytes=" + chunk.pcm().length
                            + " start=" + chunk.startNanos() + " end=" + chunk.endNanos(), ChatFormatting.GREEN);
                });
        return 1;
    }

    // ===== config =====

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> config() {
        return Commands.literal("config")
                .executes(ctx -> help(ctx, "config"))
                .then(Commands.literal("list").executes(ctx -> {
                    Map<String, Object> all = RuntimeConfigWriter.getAll();
                    int i = 0;
                    for (Map.Entry<String, Object> e : all.entrySet()) {
                        if (i++ >= CONFIG_LIST_LIMIT) {
                            reply(ctx, "... 其余 " + (all.size() - CONFIG_LIST_LIMIT) + " 项省略", ChatFormatting.GRAY);
                            break;
                        }
                        reply(ctx, e.getKey() + " = " + e.getValue(), ChatFormatting.GRAY);
                    }
                    return 1;
                }))
                .then(Commands.literal("get")
                        .then(Commands.argument("key", StringArgumentType.word())
                                .executes(ctx -> {
                                    String key = StringArgumentType.getString(ctx, "key");
                                    Object value = RuntimeConfigWriter.get(key).orElse(null);
                                    return value == null ? fail(ctx, "未知参数：" + key)
                                            : ok(ctx, key + " = " + value);
                                })))
                .then(Commands.literal("set")
                        .then(Commands.argument("key", StringArgumentType.word())
                                .then(Commands.argument("value", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            String key = StringArgumentType.getString(ctx, "key");
                                            String raw = StringArgumentType.getString(ctx, "value");
                                            JsonElement value = parseValue(raw);
                                            RuntimeConfigWriter.Result result = RuntimeConfigWriter.set(key, value);
                                            return result.applied() ? ok(ctx, result.message())
                                                    : fail(ctx, result.message());
                                        }))));
    }

    private static JsonElement parseValue(String raw) {
        String v = raw.trim();
        if ("true".equalsIgnoreCase(v) || "false".equalsIgnoreCase(v)) {
            return new JsonPrimitive(Boolean.parseBoolean(v));
        }
        try {
            return new JsonPrimitive(Long.parseLong(v));
        } catch (NumberFormatException ignored) {
            // not int
        }
        try {
            return new JsonPrimitive(Double.parseDouble(v));
        } catch (NumberFormatException ignored) {
            return new JsonPrimitive(v);
        }
    }

    // ===== filter / overlay =====

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> filter() {
        return Commands.literal("filter")
                .executes(ctx -> help(ctx, "filter"))
                .then(Commands.literal("list").executes(ctx -> {
                    FilterRegistry registry = FilterRegistry.get();
                    return ok(ctx, "filters=" + registry.names() + " selected=" + registry.selected()
                            + " applied=" + registry.appliedFrames() + " failed=" + registry.failedFilters()
                            + " lastError=" + registry.lastError());
                }))
                .then(Commands.literal("select")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> {
                                    String name = StringArgumentType.getString(ctx, "name");
                                    return FilterRegistry.get().select(name)
                                            ? ok(ctx, "已选择滤镜：" + name)
                                            : fail(ctx, "滤镜不存在或加载失败：" + name);
                                })))
                .then(Commands.literal("reload").executes(ctx -> {
                    FilterRegistry.get().reload();
                    return ok(ctx, "滤镜已重载，selected=" + FilterRegistry.get().selected());
                }));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> overlay() {
        return Commands.literal("overlay")
                .executes(ctx -> help(ctx, "overlay"))
                .then(Commands.literal("list").executes(ctx -> ok(ctx,
                        "overlay images=" + OverlayRegistry.get().images() + " active=" + OverlayRegistry.get().active())))
                .then(Commands.literal("clear").executes(ctx -> {
                    OverlayRegistry.get().clear();
                    return ok(ctx, "叠加层已清空");
                }))
                .then(Commands.literal("show")
                        .then(Commands.argument("image", StringArgumentType.word())
                                .then(Commands.argument("x", DoubleArgumentType.doubleArg(0.0D, 1.0D))
                                        .then(Commands.argument("y", DoubleArgumentType.doubleArg(0.0D, 1.0D))
                                                .then(Commands.argument("scale", DoubleArgumentType.doubleArg(0.01D, 10.0D))
                                                        .executes(ctx -> overlayShow(ctx, 1.0D))
                                                        .then(Commands.argument("opacity",
                                                                        DoubleArgumentType.doubleArg(0.0D, 1.0D))
                                                                .executes(ctx -> overlayShow(ctx,
                                                                        DoubleArgumentType.getDouble(ctx, "opacity")))))))));
    }

    private static int overlayShow(CommandContext<CommandSourceStack> ctx, double opacity) {
        try {
            OverlayModel model = new OverlayModel(StringArgumentType.getString(ctx, "image"),
                    DoubleArgumentType.getDouble(ctx, "x"), DoubleArgumentType.getDouble(ctx, "y"),
                    DoubleArgumentType.getDouble(ctx, "scale"), DoubleArgumentType.getDouble(ctx, "scale"),
                    0.0D, opacity);
            return OverlayRegistry.get().show(model) ? ok(ctx, "已显示叠加层：" + model.image())
                    : fail(ctx, "叠加层显示失败（贴图不存在？）：" + model.image());
        } catch (Throwable t) {
            return fail(ctx, "叠加层参数错误：" + t.getMessage());
        }
    }

    // ===== diagnostics / unimplemented =====

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> diagnostics() {
        return Commands.literal("status").executes(ctx -> {
            CameraSpec active = CameraManager.get().active();
            RecordingStatus rec = RecordingSession.get().status();
            reply(ctx, "camera=" + (active == null ? "none" : CameraManager.describe(active)), ChatFormatting.GRAY);
            reply(ctx, "recording=" + (rec.running() ? "running frames=" + rec.frames() : "idle")
                    + " audioAvailable=" + rec.audioAvailable(), ChatFormatting.GRAY);
            reply(ctx, "audio loopback=" + AudioCaptureManager.get().isLoopbackActive()
                    + " written=" + AudioRingBuffer.get().writtenPolls(), ChatFormatting.GRAY);
            reply(ctx, "filter=" + FilterRegistry.get().selected() + " overlayActive="
                    + OverlayRegistry.get().active(), ChatFormatting.GRAY);
            reply(ctx, "remote ready=" + RemoteCaptureClient.isReady() + " requests="
                    + RemoteCaptureClient.requests() + " accepted=" + RemoteCaptureClient.accepted(), ChatFormatting.GRAY);
            reply(ctx, "shader backend=" + ShaderCompatHooks.backend() + " detected="
                    + ShaderCompatHooks.detected(), ChatFormatting.GRAY);
            return 1;
        }).then(Commands.literal("shader").executes(ctx -> ok(ctx,
                "shader backend=" + ShaderCompatHooks.backend() + " detected=" + ShaderCompatHooks.detected()
                        + " active=" + ShaderCompatHooks.isActive() + "（W-043 未实装）")))
                .then(Commands.literal("remote").executes(ctx -> ok(ctx,
                        "remote ready=" + RemoteCaptureClient.isReady() + " requests="
                                + RemoteCaptureClient.requests() + " accepted=" + RemoteCaptureClient.accepted()
                                + " lastCode=" + RemoteCaptureClient.lastCode())));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> helpCmd() {
        return Commands.literal("help").executes(CaptureCommands::help);
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> captureAlias() {
        return Commands.literal("capture").executes(ctx -> frameCurrent(ctx, "png"));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> networkCmd() {
        return Commands.literal("network").executes(ctx -> {
            reply(ctx, "HTTP health: http://127.0.0.1:25580/health", ChatFormatting.GRAY);
            reply(ctx, "单帧: http://127.0.0.1:25580/camera/0/frame", ChatFormatting.GRAY);
            reply(ctx, "MJPEG: http://127.0.0.1:25580/camera/0/stream", ChatFormatting.GRAY);
            reply(ctx, "WS 控制/流: ws://127.0.0.1:25581/（端口取 config net_port）", ChatFormatting.GRAY);
            return 1;
        });
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> listCmd() {
        return Commands.literal("list")
                .then(Commands.literal("render").executes(ctx -> ok(ctx,
                        "render list: " + RenderFilterRegistry.get().summary()
                                + " blockedQueries=" + RenderFilterRegistry.get().blockedQueries())))
                .then(Commands.literal("audio").executes(ctx -> ok(ctx,
                        "audio list: " + AudioFilterRegistry.get().summary()
                                + " blockedQueries=" + AudioFilterRegistry.get().blockedQueries())))
                .then(Commands.literal("reload").executes(ctx -> {
                    String r = RenderFilterRegistry.get().reload();
                    String a = AudioFilterRegistry.get().reload();
                    return ok(ctx, "list reload: render[" + r + "] audio[" + a + "]（W-095 实际挂接未实现）");
                }));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> glslCmd() {
        return Commands.literal("glsl").executes(ctx -> notImplemented(ctx, "GLSL 滤镜", "P2.1 按裁剪条件暂不做，JSON 参数滤镜可用 filter 命令"));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> renderListCmd() {
        return Commands.literal("renderlist").executes(ctx -> notImplemented(ctx, "渲染名单挂接", "W-095 未实现；可用 list render 查看解析结果"));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> audioListCmd() {
        return Commands.literal("audiolist").executes(ctx -> notImplemented(ctx, "音频名单挂接", "W-095 未实现；可用 list audio 查看解析结果"));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> multicamCmd() {
        return Commands.literal("multicam").executes(ctx -> notImplemented(ctx, "多路独立渲染", "P2.3 未实现；当前仅 active 相机单路"));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> overlayGpuCmd() {
        return Commands.literal("overlaygpu").executes(ctx -> notImplemented(ctx, "GPU 叠加", "P2.4 未实现；CPU 叠加可用 overlay 命令"));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> oculusCmd() {
        return Commands.literal("oculus").executes(ctx -> notImplemented(ctx, "Oculus 离屏适配", "W-043 未实装；当前仅探测诊断"));
    }

    private static int notImplemented(CommandContext<CommandSourceStack> ctx, String feature, String detail) {
        reply(ctx, feature + "：未实现 — " + detail, ChatFormatting.YELLOW);
        return 1;
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> unimplemented() {
        return Commands.literal("unimplemented").executes(ctx -> {
            reply(ctx, "未实现/暂缓功能：", ChatFormatting.YELLOW);
            reply(ctx, "glsl：P2.1 按裁剪条件暂不做（JSON 参数滤镜可用 filter 命令）", ChatFormatting.GRAY);
            reply(ctx, "可直接输入 /captureapi glsl|renderlist|audiolist|multicam|overlaygpu|oculus 获取单项说明", ChatFormatting.GRAY);
            reply(ctx, "renderlist/audiolist：W-095 名单实际挂接未实现", ChatFormatting.GRAY);
            reply(ctx, "multicam：P2.3 多路独立渲染未实现", ChatFormatting.GRAY);
            reply(ctx, "overlaygpu：P2.4 GPU 叠加未实现（CPU 叠加可用 overlay）", ChatFormatting.GRAY);
            reply(ctx, "oculusmode：W-043 Oculus 离屏适配未实装", ChatFormatting.GRAY);
            return 1;
        });
    }

    private static int help(CommandContext<CommandSourceStack> ctx) {
        reply(ctx, "=== CaptureAPI 临时测试指令集 ===", ChatFormatting.AQUA);
        reply(ctx, "camera create/top/adjust/activate/pause/resume/delete/list/status/clear", ChatFormatting.GRAY);
        reply(ctx, "frame [top|current] [png|jpeg]  或 /get_image", ChatFormatting.GRAY);
        reply(ctx, "record start [fps] [audio|silent] / stop / status", ChatFormatting.GRAY);
        reply(ctx, "audio status / section [chunks]", ChatFormatting.GRAY);
        reply(ctx, "config list/get/set", ChatFormatting.GRAY);
        reply(ctx, "filter list/select/reload；overlay list/clear/show", ChatFormatting.GRAY);
        reply(ctx, "status [shader|remote]；network 显示 HTTP/WS 地址", ChatFormatting.GRAY);
        reply(ctx, "list render|audio|reload；unimplemented 查看未实现项", ChatFormatting.GRAY);
        reply(ctx, "未实现单项：glsl/renderlist/audiolist/multicam/overlaygpu/oculus", ChatFormatting.GRAY);
        return 1;
    }

    private static int help(CommandContext<CommandSourceStack> ctx, String group) {
        return help(ctx);
    }

    // ===== feedback helpers =====

    private static int ok(CommandContext<CommandSourceStack> ctx, String message) {
        return ok(ctx.getSource(), message);
    }

    private static int ok(CommandSourceStack source, String message) {
        source.sendSuccess(() -> Component.literal("[CaptureAPI] " + message).withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    private static int fail(CommandContext<CommandSourceStack> ctx, String message) {
        return fail(ctx.getSource(), message);
    }

    private static int fail(CommandSourceStack source, String message) {
        source.sendFailure(Component.literal("[CaptureAPI] " + message).withStyle(ChatFormatting.RED));
        return 0;
    }

    private static void reply(CommandContext<CommandSourceStack> ctx, String message, ChatFormatting color) {
        reply(ctx.getSource(), message, color);
    }

    private static void reply(CommandSourceStack source, String message, ChatFormatting color) {
        source.sendSuccess(() -> Component.literal("[CaptureAPI] " + message).withStyle(color), false);
    }

    private static void replyMain(CommandSourceStack source, String message, ChatFormatting color) {
        Minecraft.getInstance().execute(() -> reply(source, message, color));
    }
}
