package com.captureapi.net.control;

import com.captureapi.core.constant.CaptureConstants;
import com.captureapi.net.FrameRequest;
import com.captureapi.video.camera.CameraCreateParams;
import com.captureapi.video.camera.CameraManager;
import com.captureapi.video.camera.CameraSpec;
import com.captureapi.video.camera.ShapeModel;
import com.captureapi.core.config.RuntimeConfigWriter;
import com.captureapi.video.filter.FilterRegistry;
import com.captureapi.video.overlay.OverlayModel;
import com.captureapi.video.overlay.OverlayRegistry;
import java.io.File;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import net.minecraft.client.gui.screens.TitleScreen;
import com.captureapi.core.time.CaptureClock;
import com.captureapi.video.filter.RenderFilterRegistry;
import com.captureapi.audio.AudioFilterRegistry;
import com.captureapi.audio.timestamp.AudioChunk;
import com.captureapi.net.CaptureApi;
import com.captureapi.net.recording.RecordingParams;
import com.captureapi.net.recording.RecordingSession;
import com.captureapi.net.recording.RecordingStatus;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * WS 控制指令分发与校验（I4.3.2/I4.3.3）。
 *
 * <p>信封 `{id, action, params}`；action 白名单路由到 {@link CameraManager}；参数在入口校验
 * （范围/类型/UUID），非法即结构化错误码拒绝。权限：当前客户端为 SP/LAN（T4.3 约定恒通过），
 * 专用服权限归 M9/T4.3 follow-up。响应 `{id, ok, result?}` 或 `{id, ok:false, error:{code,message}}`。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class ControlCommandDispatcher {

    private static final Logger LOGGER = LoggerFactory.getLogger(CaptureConstants.LOG_MARKER);
    /** 音频截面指令等待上限（秒）。 */
    private static final long AUDIO_SECTION_TIMEOUT_SECONDS = 5L;
    private static final Gson GSON = new Gson();
    private static final double OVERLAY_DEFAULT_X = 0.5D;
    private static final double OVERLAY_DEFAULT_Y = 0.5D;
    private static final double OVERLAY_DEFAULT_SCALE = 1.0D;
    private static final double OVERLAY_DEFAULT_ROTATION = 0.0D;
    private static final double OVERLAY_DEFAULT_OPACITY = 1.0D;
    private static final ControlCommandDispatcher INSTANCE = new ControlCommandDispatcher();

    private ControlCommandDispatcher() {
    }

    public static ControlCommandDispatcher get() {
        return INSTANCE;
    }

    /**
     * 处理一条 WS 文本消息并发送响应。
     *
     * @param message  原始 JSON
     * @param response 响应发送器（客户端 WS 文本发送）
     */
    public void handle(String message, Consumer<String> response) {
        String id = "";
        try {
            JsonObject root = JsonParser.parseString(message).getAsJsonObject();
            id = optString(root, "id", "");
            String action = optString(root, "action", "").trim();
            JsonObject params = root.has("params") && root.get("params").isJsonObject()
                    ? root.getAsJsonObject("params") : new JsonObject();
            if (action.isEmpty()) {
                response.accept(error(id, ControlErrorCode.E_MISSING_PARAM, "缺少 action"));
                return;
            }
            JsonElement result = dispatch(action, params);
            JsonObject ok = new JsonObject();
            ok.addProperty("id", id);
            ok.addProperty("ok", true);
            if (result != null) {
                ok.add("result", result);
            }
            response.accept(GSON.toJson(ok));
        } catch (ControlException e) {
            response.accept(error(id, e.code, e.getMessage()));
        } catch (Throwable t) {
            LOGGER.warn("{} [CTRL] 指令处理异常", CaptureConstants.LOG_MARKER, t);
            response.accept(error(id, ControlErrorCode.E_INTERNAL, "内部错误"));
        }
    }

    private JsonElement dispatch(String action, JsonObject params) {
        TestCommandGate.require(action);
        return switch (action) {
            case "capture" -> unifiedCapture(params);
            case "camera.create" -> create(params);
            case "camera.adjust" -> adjust(params);
            case "camera.activate" -> activate(params);
            case "camera.pause" -> pauseResume(params, true);
            case "camera.resume" -> pauseResume(params, false);
            case "camera.delete" -> delete(params);
            case "camera.list" -> list();
            case "camera.status" -> status();
            case "audio.section" -> audioSection(params);
            case "audio.channels.list" -> audioChannels();
            case "audio.channels.peek" -> audioChannelPeek(params);
            case "shader.status" -> shaderStatus();
            case "recording.start" -> recordingStart(params);
            case "recording.stop" -> recordingStop();
            case "recording.status" -> recordingStatus();
            case "config.get" -> configGet(params);
            case "config.set" -> configSet(params);
            case "filter.list" -> filterList();
            case "filter.set" -> filterSet(params);
            case "filter.reload" -> filterReload();
            case "overlay.list" -> overlayList();
            case "overlay.reload" -> overlayReload();
            case "overlay.show" -> overlayShow(params);
            case "overlay.clear" -> overlayClear();
            case "renderfilter.list" -> renderFilterList();
            case "renderfilter.reload" -> renderFilterReload();
            case "audiofilter.list" -> audioFilterList();
            case "audiofilter.reload" -> audioFilterReload();
            case "client.unpause" -> clientUnpause();
            case "client.playerPose" -> clientPlayerPose();
            case "client.screenshot" -> clientScreenshot();
            case "client.leaveWorld" -> clientLeaveWorld();
            case "client.loadWorld" -> clientLoadWorld(params);
            case "client.playTestSound" -> clientPlayTestSound();
            case "client.playPosSound" -> clientPlayPosSound(params);
            default -> throw new ControlException(ControlErrorCode.E_UNKNOWN_ACTION, "未知 action：" + action);
        };
    }

    /**
     * 统一 capture action：JSON 参数 → FrameRequest → CaptureApi.capture()。
     * 与游戏指令和 Java API 调用同一函数，参数一一对应。
     */
    private JsonElement unifiedCapture(JsonObject params) {
        try {
            FrameRequest.Builder builder = FrameRequest.builder();
            if (params.has("pos")) {
                var a = params.getAsJsonArray("pos");
                builder.position(a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble());
            }
            if (params.has("rot")) {
                var a = params.getAsJsonArray("rot");
                builder.rotation(a.get(0).getAsFloat(), a.get(1).getAsFloat(),
                        a.size() > 2 ? a.get(2).getAsFloat() : 0f);
            }
            if (params.has("fov")) builder.fov(params.get("fov").getAsDouble());
            if (params.has("res")) {
                var a = params.getAsJsonArray("res");
                builder.resolution(a.get(0).getAsInt(), a.get(1).getAsInt());
            }
            if (params.has("quality")) builder.quality(params.get("quality").getAsInt());
            if (params.has("filter")) builder.filter(params.get("filter").getAsString());
            if (params.has("overlay")) {
                var ov = params.getAsJsonObject("overlay");
                builder.overlay(new com.captureapi.video.overlay.OverlayModel(
                        ov.get("image").getAsString(),
                        ov.has("x") ? ov.get("x").getAsDouble() : OVERLAY_DEFAULT_X,
                        ov.has("y") ? ov.get("y").getAsDouble() : OVERLAY_DEFAULT_Y,
                        ov.has("scaleX") ? ov.get("scaleX").getAsDouble() : OVERLAY_DEFAULT_SCALE,
                        ov.has("scaleY") ? ov.get("scaleY").getAsDouble() : OVERLAY_DEFAULT_SCALE,
                        ov.has("rotation") ? ov.get("rotation").getAsDouble() : OVERLAY_DEFAULT_ROTATION,
                        ov.has("opacity") ? ov.get("opacity").getAsDouble() : OVERLAY_DEFAULT_OPACITY));
            }
            if (params.has("overlays")) {
                var list = new java.util.ArrayList<com.captureapi.video.overlay.OverlayModel>();
                for (var el : params.getAsJsonArray("overlays")) {
                    var ov = el.getAsJsonObject();
                    list.add(new com.captureapi.video.overlay.OverlayModel(
                            ov.get("image").getAsString(),
                            ov.has("x") ? ov.get("x").getAsDouble() : OVERLAY_DEFAULT_X,
                            ov.has("y") ? ov.get("y").getAsDouble() : OVERLAY_DEFAULT_Y,
                            ov.has("scaleX") ? ov.get("scaleX").getAsDouble() : OVERLAY_DEFAULT_SCALE,
                            ov.has("scaleY") ? ov.get("scaleY").getAsDouble() : OVERLAY_DEFAULT_SCALE,
                            ov.has("rotation") ? ov.get("rotation").getAsDouble() : OVERLAY_DEFAULT_ROTATION,
                            ov.has("opacity") ? ov.get("opacity").getAsDouble() : OVERLAY_DEFAULT_OPACITY));
                }
                builder.overlays(list);
            }
            if (params.has("out")) builder.outputPath(java.nio.file.Path.of(params.get("out").getAsString()));
            if (params.has("method")) {
                builder.outputMethod(FrameRequest.OutputMethod.valueOf(params.get("method").getAsString().toUpperCase()));
            }
            if (params.has("format")) {
                builder.format(FrameRequest.Format.valueOf(params.get("format").getAsString().toUpperCase()));
            }
            if (params.has("audio") && params.get("audio").getAsBoolean()) {
                builder.audio(true, params.has("audioChunks") ? params.get("audioChunks").getAsInt() : 1);
            }
            if (params.has("bound")) builder.boundEntity(UUID.fromString(params.get("bound").getAsString()));
            if (params.has("temporary")) builder.temporary(params.get("temporary").getAsBoolean());
            if (params.has("timeout")) builder.timeoutMillis(params.get("timeout").getAsLong());
            var outcome = com.captureapi.net.CaptureApi.capture(builder.build());
            JsonObject result = new JsonObject();
            result.addProperty("outputMethod", outcome.frame().outputMethod().name());
            result.addProperty("location", outcome.frame().location());
            result.addProperty("timestampNanos", outcome.frame().timestampNanos());
            result.addProperty("width", outcome.frame().width());
            result.addProperty("height", outcome.frame().height());
            result.addProperty("sizeBytes", outcome.frame().sizeBytes());
            if (outcome.audio() != null) {
                JsonObject audio = new JsonObject();
                audio.addProperty("sampleCount", outcome.audio().sampleCount());
                audio.addProperty("startNanos", outcome.audio().startNanos());
                audio.addProperty("endNanos", outcome.audio().endNanos());
                audio.addProperty("pcmBase64", java.util.Base64.getEncoder().encodeToString(outcome.audio().pcm()));
                result.add("audio", audio);
            }
            return result;
        } catch (ControlException e) { throw e;
        } catch (Exception e) { throw new ControlException(ControlErrorCode.E_INTERNAL, "capture 失败：" + e.getMessage()); }
    }

    private JsonElement create(JsonObject params) {
        try {
            CameraSpec spec = CameraManager.get().create(buildParams(params));
            return toJson(spec);
        } catch (IllegalStateException e) {
            String message = e.getMessage() == null ? "相机创建失败" : e.getMessage();
            if (message.startsWith("E_CAMERA_LIMIT")) {
                throw new ControlException(ControlErrorCode.E_RATE_LIMIT, message);
            }
            throw new ControlException(ControlErrorCode.E_INTERNAL, message);
        }
    }

    private JsonElement adjust(JsonObject params) {
        int id = requiredInt(params, "id");
        CameraSpec spec = CameraManager.get().adjust(id, buildParams(params));
        return toJson(spec);
    }

    private JsonElement activate(JsonObject params) {
        int id = requiredInt(params, "id");
        try {
            return toJson(CameraManager.get().activate(id));
        } catch (IllegalArgumentException e) {
            throw new ControlException(ControlErrorCode.E_NOT_FOUND, "相机不存在：" + id);
        }
    }

    private JsonElement pauseResume(JsonObject params, boolean pause) {
        int id = requiredInt(params, "id");
        try {
            return toJson(pause ? CameraManager.get().pause(id) : CameraManager.get().resume(id));
        } catch (IllegalArgumentException e) {
            throw new ControlException(ControlErrorCode.E_NOT_FOUND, "相机不存在：" + id);
        }
    }

    private JsonElement delete(JsonObject params) {
        int id = requiredInt(params, "id");
        if (CameraManager.get().get(id) == null) {
            throw new ControlException(ControlErrorCode.E_NOT_FOUND, "相机不存在：" + id);
        }
        CameraManager.get().delete(id);
        JsonObject result = new JsonObject();
        result.addProperty("deleted", id);
        result.addProperty("active", CameraManager.get().activeCameraId());
        return result;
    }

    private JsonElement list() {
        List<CameraSpec> cameras = CameraManager.get().list();
        JsonObject result = new JsonObject();
        result.addProperty("active", CameraManager.get().activeCameraId());
        JsonArray array = new JsonArray();
        for (CameraSpec spec : cameras) {
            array.add(toJson(spec));
        }
        result.add("cameras", array);
        return result;
    }

    private JsonElement configGet(JsonObject params) {
        String key = optString(params, "key", "");
        JsonObject result = new JsonObject();
        if (key.isEmpty()) {
            for (var entry : RuntimeConfigWriter.getAll().entrySet()) {
                addValue(result, entry.getKey(), entry.getValue());
            }
            return result;
        }
        Object value = RuntimeConfigWriter.get(key).orElse(null);
        if (value == null) {
            throw new ControlException(ControlErrorCode.E_NOT_FOUND, "未知参数：" + key);
        }
        result.addProperty("key", key);
        addValue(result, "value", value);
        return result;
    }

    private JsonElement configSet(JsonObject params) {
        String key = optString(params, "key", "");
        if (key.isEmpty()) {
            throw new ControlException(ControlErrorCode.E_MISSING_PARAM, "缺少参数：key");
        }
        if (!params.has("value")) {
            throw new ControlException(ControlErrorCode.E_MISSING_PARAM, "缺少参数：value");
        }
        RuntimeConfigWriter.Result result = RuntimeConfigWriter.set(key, params.get("value"));
        if (!result.applied()) {
            throw new ControlException(ControlErrorCode.E_PARAM_RANGE, result.message());
        }
        JsonObject json = new JsonObject();
        json.addProperty("key", result.key());
        addValue(json, "value", result.value());
        json.addProperty("persisted", result.persisted());
        json.addProperty("message", result.message());
        return json;
    }

    private static void addValue(JsonObject target, String key, Object value) {
        if (value instanceof Boolean b) {
            target.addProperty(key, b);
        } else if (value instanceof Integer i) {
            target.addProperty(key, i);
        } else if (value instanceof Double d) {
            target.addProperty(key, d);
        } else {
            target.addProperty(key, String.valueOf(value));
        }
    }

    private JsonElement filterList() {
        JsonObject result = new JsonObject();
        result.addProperty("selected", FilterRegistry.get().selected());
        var array = new com.google.gson.JsonArray();
        for (String name : FilterRegistry.get().names()) {
            array.add(name);
        }
        result.add("filters", array);
        return result;
    }

    private JsonElement filterSet(JsonObject params) {
        String name = optString(params, "name", "");
        boolean ok = FilterRegistry.get().select(name);
        if (!ok) {
            throw new ControlException(ControlErrorCode.E_NOT_FOUND,
                    FilterRegistry.get().lastError());
        }
        JsonObject result = new JsonObject();
        result.addProperty("selected", FilterRegistry.get().selected());
        return result;
    }

    private JsonElement filterReload() {
        FilterRegistry.get().reload();
        return filterList();
    }

    private JsonElement overlayList() {
        JsonObject result = new JsonObject();
        var images = new com.google.gson.JsonArray();
        for (String image : OverlayRegistry.get().images()) {
            images.add(image);
        }
        result.add("images", images);
        result.addProperty("enabled", com.captureapi.core.config.CaptureParams.get().overlayEnabled());
        var layerArray = new com.google.gson.JsonArray();
        for (OverlayModel layer : OverlayRegistry.get().layers()) {
            JsonObject layerJson = new JsonObject();
            layerJson.addProperty("image", layer.image());
            layerJson.addProperty("x", layer.x());
            layerJson.addProperty("y", layer.y());
            layerJson.addProperty("scaleX", layer.scaleX());
            layerJson.addProperty("scaleY", layer.scaleY());
            layerJson.addProperty("rotation", layer.rotation());
            layerJson.addProperty("opacity", layer.opacity());
            layerArray.add(layerJson);
        }
        result.add("layers", layerArray);
        return result;
    }

    private JsonElement overlayReload() {
        OverlayRegistry.get().reload();
        return overlayList();
    }

    private JsonElement overlayShow(JsonObject params) {
        String image = optString(params, "image", "");
        if (image.isEmpty()) {
            throw new ControlException(ControlErrorCode.E_MISSING_PARAM, "缺少参数：image");
        }
        OverlayModel model;
        try {
            model = new OverlayModel(image, optDouble(params, "x", 0.5D), optDouble(params, "y", 0.5D),
                    optDouble(params, "scaleX", 1.0D), optDouble(params, "scaleY", 1.0D),
                    optDouble(params, "rotation", 0.0D), optDouble(params, "opacity", 1.0D));
        } catch (IllegalArgumentException e) {
            throw new ControlException(ControlErrorCode.E_PARAM_RANGE, e.getMessage());
        }
        if (!OverlayRegistry.get().show(model)) {
            throw new ControlException(ControlErrorCode.E_NOT_FOUND, "叠加贴图不存在：" + image);
        }
        return overlayList();
    }

    private JsonElement overlayClear() {
        OverlayRegistry.get().clear();
        return overlayList();
    }

    private JsonElement renderFilterList() {
        JsonObject result = new JsonObject();
        result.addProperty("summary", RenderFilterRegistry.get().summary());
        return result;
    }

    private JsonElement renderFilterReload() {
        RenderFilterRegistry.get().reload();
        return renderFilterList();
    }

    private JsonElement audioFilterList() {
        JsonObject result = new JsonObject();
        result.addProperty("summary", AudioFilterRegistry.get().summary());
        return result;
    }

    private JsonElement audioFilterReload() {
        AudioFilterRegistry.get().reload();
        return audioFilterList();
    }

    /**
     * 自动化验收辅助：关闭暂停界面并禁用失焦自动暂停（仅本地调试用）。
     * 游戏暂停时世界不渲染，S1 自动化取帧会冻结；此指令把客户端恢复为运行态。
     */
    private JsonElement clientUnpause() {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            minecraft.options.pauseOnLostFocus = false;
            if (minecraft.screen != null && minecraft.screen.isPauseScreen()) {
                minecraft.setScreen(null);
            }
        });
        JsonObject result = new JsonObject();
        result.addProperty("unpaused", true);
        return result;
    }

    private JsonElement clientPlayerPose() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            throw new ControlException(ControlErrorCode.E_INTERNAL, "玩家尚未进入世界");
        }
        JsonObject result = new JsonObject();
        result.addProperty("x", minecraft.player.getX());
        result.addProperty("y", minecraft.player.getY());
        result.addProperty("z", minecraft.player.getZ());
        result.addProperty("eyeY", minecraft.player.getEyeY());
        result.addProperty("yaw", minecraft.player.getYRot());
        result.addProperty("pitch", minecraft.player.getXRot());
        result.addProperty("fov", minecraft.options.fov().get());
        return result;
    }

    /**
     * 自动化验收辅助：通过 Minecraft 截图接口保存主画面（不依赖窗口焦点/前台）。
     *
     * @return 相对游戏目录的截图路径（异步写入，调用方轮询文件）
     */
    private JsonElement clientScreenshot() {
        Minecraft minecraft = Minecraft.getInstance();
        String name = "captureapi-" + CaptureClock.get().nowNanos();
        minecraft.execute(() -> Screenshot.grab(minecraft.gameDirectory, name,
                minecraft.getMainRenderTarget(), component -> { }));
        JsonObject result = new JsonObject();
        result.addProperty("path", "screenshots/" + name + ".png");
        return result;
    }

    /** 测试辅助：退出当前世界到标题界面（S1.4 进出世界自动化）。 */
    private JsonElement clientLeaveWorld() {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            if (minecraft.level != null) {
                minecraft.level.disconnect();
            }
            minecraft.clearLevel(new TitleScreen());
        });
        JsonObject result = new JsonObject();
        result.addProperty("leaving", true);
        return result;
    }

    /** 测试辅助：从标题界面加载指定存档（S1.4 进出世界自动化）。 */
    private JsonElement clientLoadWorld(JsonObject params) {
        String name = optString(params, "name", "s1test");
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> minecraft.createWorldOpenFlows().loadLevel(new TitleScreen(), name));
        JsonObject result = new JsonObject();
        result.addProperty("loading", name);
        return result;
    }

    /** 测试辅助：播放一个已知 UI 铃铛音效（S2 音频验收）。 */
    private JsonElement clientPlayTestSound() {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> minecraft.getSoundManager()
                .play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BELL, 1.0F)));
        JsonObject result = new JsonObject();
        result.addProperty("played", "note_block.bell");
        return result;
    }

    /** 测试辅助：在玩家周围指定偏移播放一个 3D 音符盒声源（S2.4 方向性验证）。 */
    private JsonElement clientPlayPosSound(JsonObject params) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            throw new ControlException(ControlErrorCode.E_INTERNAL, "玩家尚未进入世界");
        }
        double dx = optDouble(params, "dx", 0.0D);
        double dy = optDouble(params, "dy", 0.0D);
        double dz = optDouble(params, "dz", 0.0D);
        double volume = optDouble(params, "volume", 1.0D);
        Vec3 eye = minecraft.player.getEyePosition();
        double x = eye.x + dx;
        double y = eye.y + dy;
        double z = eye.z + dz;
        minecraft.execute(() -> minecraft.getSoundManager().play(new SimpleSoundInstance(
                SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.BLOCKS, (float) volume, 1.0F,
                RandomSource.create(), x, y, z)));
        JsonObject result = new JsonObject();
        result.addProperty("x", x);
        result.addProperty("y", y);
        result.addProperty("z", z);
        result.addProperty("volume", volume);
        return result;
    }

    /** 光影/渲染环境状态（P1.5 诊断）。 */
    private JsonElement shaderStatus() {
        JsonObject result = new JsonObject();
        result.addProperty("detected", com.captureapi.video.shader.ShaderCompatHooks.detected());
        result.addProperty("active", com.captureapi.video.shader.ShaderCompatHooks.isActive());
        result.addProperty("backend", com.captureapi.video.shader.ShaderCompatHooks.backend());
        return result;
    }

    /** 测试辅助：音频截面（W-071）调用并返回 PCM 统计，不返回原始大数组。 */
    private JsonElement audioChannelPeek(JsonObject params) {
        int channel = optInt(params, "channel", 0);
        AudioChunk chunk = CaptureApi.latestAudioChunk(channel);
        JsonObject result = new JsonObject();
        result.addProperty("channel", channel);
        if (chunk == null) {
            result.addProperty("found", false);
            return result;
        }
        result.addProperty("found", true);
        result.addProperty("startNanos", chunk.startNanos());
        result.addProperty("endNanos", chunk.endNanos());
        result.addProperty("sampleCount", chunk.sampleCount());
        result.addProperty("rms", rms(chunk.pcm()));
        result.addProperty("peak", peak(chunk.pcm()));
        return result;
    }

    private static double rms(byte[] pcm) {
        if (pcm == null || pcm.length < 2) {
            return 0.0D;
        }
        double sum = 0.0D;
        int count = pcm.length / 2;
        for (int i = 0; i < count; i++) {
            short sample = (short) (((pcm[i * 2 + 1] & 0xFF) << 8) | (pcm[i * 2] & 0xFF));
            double value = sample / (double) Short.MAX_VALUE;
            sum += value * value;
        }
        return Math.sqrt(sum / count);
    }

    private static int peak(byte[] pcm) {
        int peak = 0;
        if (pcm != null) {
            for (int i = 0; i + 1 < pcm.length; i += 2) {
                int value = Math.abs((short) (((pcm[i + 1] & 0xFF) << 8) | (pcm[i] & 0xFF)));
                peak = Math.max(peak, value);
            }
        }
        return peak;
    }

    private JsonElement audioChannels() {
        JsonObject result = new JsonObject();
        JsonArray array = new JsonArray();
        for (com.captureapi.audio.multi.AudioChannelInfo info
                : com.captureapi.audio.multi.AudioChannelRegistry.get().channels()) {
            JsonObject item = new JsonObject();
            item.addProperty("id", info.id());
            item.addProperty("kind", info.kind().name());
            item.addProperty("cameraId", info.cameraId());
            item.addProperty("active", info.active());
            item.addProperty("written", info.written());
            item.addProperty("dropped", info.dropped());
            item.addProperty("errors", info.errors());
            array.add(item);
        }
        result.add("channels", array);
        result.addProperty("primary", com.captureapi.audio.multi.AudioChannelRegistry.get().primaryChannelId());
        return result;
    }

    private JsonElement audioSection(JsonObject params) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            throw new ControlException(ControlErrorCode.E_INTERNAL, "玩家尚未进入世界");
        }
        double x = optDouble(params, "x", minecraft.player.getEyePosition().x);
        double y = optDouble(params, "y", minecraft.player.getEyePosition().y);
        double z = optDouble(params, "z", minecraft.player.getEyePosition().z);
        int chunks = optInt(params, "chunks", 3);
        if (chunks < 1 || chunks > 100) {
            throw new ControlException(ControlErrorCode.E_PARAM_RANGE, "chunks 范围 [1,100]");
        }
        try {
            AudioChunk chunk = CaptureApi.grabAudioSection(x, y, z, chunks).get(AUDIO_SECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            byte[] pcm = chunk.pcm();
            long sum = 0L;
            int peak = 0;
            int samples = pcm.length / 2;
            for (int i = 0; i + 1 < pcm.length; i += 2) {
                int v = (short) ((pcm[i] & 0xFF) | (pcm[i + 1] << 8));
                sum += (long) v * v;
                peak = Math.max(peak, Math.abs(v));
            }
            double rms = samples > 0 ? Math.sqrt(sum / (double) samples) : 0.0D;
            JsonObject result = new JsonObject();
            result.addProperty("start", chunk.startNanos());
            result.addProperty("end", chunk.endNanos());
            result.addProperty("samples", chunk.sampleCount());
            result.addProperty("bytes", pcm.length);
            result.addProperty("rms", rms);
            result.addProperty("peak", peak);
            return result;
        } catch (Exception e) {
            throw new ControlException(ControlErrorCode.E_INTERNAL, "音频截面失败：" + e.getMessage());
        }
    }

    private JsonElement recordingStart(JsonObject params) {
        int cameraId = optInt(params, "cameraId", 0);
        int fps = optInt(params, "fps", com.captureapi.core.config.CaptureParams.get().fpsMax());
        int quality = optInt(params, "jpegQuality",
                com.captureapi.core.config.CaptureParams.get().jpegQuality());
        boolean includeAudio = false;
        if (params.has("includeAudio") && !params.get("includeAudio").isJsonNull()) {
            try {
                includeAudio = params.get("includeAudio").getAsBoolean();
            } catch (RuntimeException e) {
                throw new ControlException(ControlErrorCode.E_PARAM_RANGE, "includeAudio 必须为布尔值");
            }
        }
        String outputPath = params.has("outputPath") && !params.get("outputPath").isJsonNull()
                ? params.get("outputPath").getAsString() : null;
        int audioChannel = optInt(params, "audioChannel", -1);
        try {
            RecordingStatus status = RecordingSession.get().start(
                    new RecordingParams(cameraId, fps, quality, includeAudio, outputPath, audioChannel));
            return recordingStatusJson(status);
        } catch (RuntimeException e) {
            throw new ControlException(ControlErrorCode.E_INTERNAL, e.getMessage());
        }
    }

    private JsonElement recordingStop() {
        return recordingStatusJson(RecordingSession.get().stop());
    }

    private JsonElement recordingStatus() {
        return recordingStatusJson(RecordingSession.get().status());
    }

    private static JsonObject recordingStatusJson(RecordingStatus status) {
        JsonObject json = new JsonObject();
        json.addProperty("running", status.running());
        json.addProperty("frames", status.frames());
        if (status.outputPath() != null) {
            json.addProperty("path", status.outputPath());
        }
        json.addProperty("audioAvailable", status.audioAvailable());
        if (status.error() != null) {
            json.addProperty("error", status.error());
        }
        return json;
    }

    private JsonElement status() {
        JsonObject result = new JsonObject();
        result.addProperty("active", CameraManager.get().activeCameraId());
        result.addProperty("count", CameraManager.get().size());
        CameraSpec active = CameraManager.get().active();
        if (active != null) {
            result.add("camera", toJson(active));
        }
        return result;
    }

    private CameraCreateParams buildParams(JsonObject params) {
        CameraCreateParams.Builder builder = CameraCreateParams.builder();
        double x = optDouble(params, "x", 0.0D);
        double y = optDouble(params, "y", 0.0D);
        double z = optDouble(params, "z", 0.0D);
        builder.position(x, y, z);
        float yaw = (float) optDouble(params, "yaw", 0.0D);
        float pitch = (float) optDouble(params, "pitch", 0.0D);
        float roll = (float) optDouble(params, "roll", 0.0D);
        if (pitch < -90.0F || pitch > 90.0F) {
            throw new ControlException(ControlErrorCode.E_PARAM_RANGE, "pitch 必须在 [-90,90]");
        }
        builder.rotation(yaw, pitch, roll);
        double fov = optDouble(params, "fov", CameraCreateParams.DEFAULT_FOV_DEGREES);
        if (fov <= 1.0D || fov >= 179.0D) {
            throw new ControlException(ControlErrorCode.E_PARAM_RANGE, "fov 必须在 (1,179)");
        }
        builder.fov(fov);
        int width = optInt(params, "width", 0);
        int height = optInt(params, "height", 0);
        if ((width != 0 || height != 0) && (width <= 0 || height <= 0)) {
            throw new ControlException(ControlErrorCode.E_PARAM_RANGE, "width/height 必须同时为正或同时省略");
        }
        builder.resolution(width, height);
        String shape = optString(params, "shape", optString(params, "projectionId", "rect"));
        if (shape.isEmpty()) {
            throw new ControlException(ControlErrorCode.E_PARAM_RANGE, "shape 不能为空");
        }
        if (ShapeModel.SPHERE_CONE.equals(shape)) {
            if (!params.has("angle") || params.get("angle").isJsonNull()) {
                throw new ControlException(ControlErrorCode.E_MISSING_PARAM, "缺少参数：angle（球面锥开角）");
            }
            if (!params.has("axisLength") || params.get("axisLength").isJsonNull()) {
                throw new ControlException(ControlErrorCode.E_MISSING_PARAM, "缺少参数：axisLength（球面锥轴长）");
            }
            double angle = optDouble(params, "angle", 0.0D);
            double axisLength = optDouble(params, "axisLength", 0.0D);
            float axisYaw = (float) optDouble(params, "axisYaw", 0.0D);
            float axisPitch = (float) optDouble(params, "axisPitch", 0.0D);
            try {
                builder.shape(ShapeModel.sphereCone(angle, axisLength, axisYaw, axisPitch));
            } catch (IllegalArgumentException e) {
                throw new ControlException(ControlErrorCode.E_PARAM_RANGE, e.getMessage());
            }
        } else {
            try {
                builder.shape(ShapeModel.of(shape, 0.0D, 0.0D, 0.0F, 0.0F));
            } catch (IllegalArgumentException e) {
                throw new ControlException(ControlErrorCode.E_PARAM_RANGE, e.getMessage());
            }
        }
        if (params.has("boundEntity") && !params.get("boundEntity").isJsonNull()) {
            String uuidText = params.get("boundEntity").getAsString();
            UUID uuid;
            try {
                uuid = UUID.fromString(uuidText);
            } catch (IllegalArgumentException e) {
                throw new ControlException(ControlErrorCode.E_PARAM_RANGE, "boundEntity 不是合法 UUID");
            }
            builder.boundEntity(uuid, optDouble(params, "offsetX", 0.0D),
                    optDouble(params, "offsetY", 0.0D), optDouble(params, "offsetZ", 0.0D));
        }
        try {
            return builder.build();
        } catch (RuntimeException e) {
            throw new ControlException(ControlErrorCode.E_PARAM_RANGE, e.getMessage());
        }
    }

    private static JsonObject toJson(CameraSpec spec) {
        JsonObject json = new JsonObject();
        json.addProperty("id", spec.id());
        json.addProperty("x", spec.positionX());
        json.addProperty("y", spec.positionY());
        json.addProperty("z", spec.positionZ());
        json.addProperty("yaw", spec.yawDegrees());
        json.addProperty("pitch", spec.pitchDegrees());
        json.addProperty("roll", spec.rollDegrees());
        json.addProperty("fov", spec.fovDegrees());
        json.addProperty("width", spec.width());
        json.addProperty("height", spec.height());
        json.addProperty("paused", spec.paused());
        json.addProperty("shape", spec.projectionId());
        if (spec.shape().isSphereCone()) {
            json.addProperty("angle", spec.shape().angleDegrees());
            json.addProperty("axisLength", spec.shape().axisLength());
            json.addProperty("axisYaw", spec.shape().axisYaw());
            json.addProperty("axisPitch", spec.shape().axisPitch());
        }
        if (spec.boundEntityUuid() != null) {
            json.addProperty("boundEntity", spec.boundEntityUuid().toString());
            json.addProperty("offsetX", spec.boundOffsetX());
            json.addProperty("offsetY", spec.boundOffsetY());
            json.addProperty("offsetZ", spec.boundOffsetZ());
        }
        return json;
    }

    private static String error(String id, String code, String message) {
        JsonObject json = new JsonObject();
        json.addProperty("id", id);
        json.addProperty("ok", false);
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message == null ? "" : message);
        json.add("error", error);
        return GSON.toJson(json);
    }

    private static String optString(JsonObject object, String name, String fallback) {
        if (!object.has(name) || object.get(name).isJsonNull()) {
            return fallback;
        }
        try {
            return object.get(name).getAsString();
        } catch (RuntimeException e) {
            throw new ControlException(ControlErrorCode.E_PARAM_RANGE, name + " 类型错误");
        }
    }

    private static double optDouble(JsonObject object, String name, double fallback) {
        if (!object.has(name) || object.get(name).isJsonNull()) {
            return fallback;
        }
        try {
            double value = object.get(name).getAsDouble();
            if (!Double.isFinite(value)) {
                throw new NumberFormatException();
            }
            return value;
        } catch (RuntimeException e) {
            throw new ControlException(ControlErrorCode.E_PARAM_RANGE, name + " 必须为有限数");
        }
    }

    private static int optInt(JsonObject object, String name, int fallback) {
        if (!object.has(name) || object.get(name).isJsonNull()) {
            return fallback;
        }
        try {
            return object.get(name).getAsInt();
        } catch (RuntimeException e) {
            throw new ControlException(ControlErrorCode.E_PARAM_RANGE, name + " 必须为整数");
        }
    }

    private static int requiredInt(JsonObject params, String name) {
        if (!params.has(name) || params.get(name).isJsonNull()) {
            throw new ControlException(ControlErrorCode.E_MISSING_PARAM, "缺少参数：" + name);
        }
        try {
            return params.get(name).getAsInt();
        } catch (RuntimeException e) {
            throw new ControlException(ControlErrorCode.E_PARAM_RANGE, name + " 必须为整数");
        }
    }

    /** 控制异常（携带稳定错误码）。 */
    static final class ControlException extends RuntimeException {
        private final String code;

        ControlException(String code, String message) {
            super(message);
            this.code = code;
        }
    }
}
