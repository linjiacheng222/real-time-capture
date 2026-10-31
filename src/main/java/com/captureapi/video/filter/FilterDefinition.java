package com.captureapi.video.filter;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 滤镜定义（T8.1 / I8.1.2）。
 *
 * <p>JSON 参数式：`{name, version, ops:[{type, params:{...}}]}`；首版 op 集：
 * brightness/contrast/saturation/hue/grayscale/invert（CPU 逐像素，按序执行）。
 * 校验规则与执行共用同一 op 表（单点）。</p>
 *
 * @param name 滤镜名（文件即滤镜）
 * @param ops  按序操作
 */
public record FilterDefinition(String name, List<Op> ops) {

    /** 单个操作。 */
    public record Op(String type, Map<String, Double> params) {
    }

    public FilterDefinition {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("滤镜名不能为空");
        }
        ops = List.copyOf(ops);
        if (ops.isEmpty()) {
            throw new IllegalArgumentException("滤镜 ops 不能为空");
        }
    }

    /**
     * 解析并校验（失败抛 {@link IllegalArgumentException}，message 含 op 序号/字段/范围）。
     *
     * @param json       滤镜 JSON
     * @param sourceName 文件名（错误提示用）
     * @return 滤镜定义
     */
    public static FilterDefinition parse(JsonObject json, String sourceName) {
        if (json.has("glsl")) {
            throw new IllegalArgumentException("GLSL-as-JSON 暂未启用（可选格式，I8.1.4 可行性门未通过）");
        }
        String name = json.has("name") ? requireString(json, "name") : sourceName;
        if (!json.has("ops") || !json.get("ops").isJsonArray()) {
            throw new IllegalArgumentException("缺少 ops 数组");
        }
        JsonArray array = json.getAsJsonArray("ops");
        List<Op> ops = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            if (!array.get(i).isJsonObject()) {
                throw new IllegalArgumentException("ops[" + i + "] 必须为对象");
            }
            JsonObject opJson = array.get(i).getAsJsonObject();
            String type = requireString(opJson, "type").toLowerCase(Locale.ROOT);
            Map<String, Double> params = new java.util.LinkedHashMap<>();
            if (opJson.has("params")) {
                if (!opJson.get("params").isJsonObject()) {
                    throw new IllegalArgumentException("ops[" + i + "].params 必须为对象");
                }
                for (Map.Entry<String, JsonElement> entry : opJson.getAsJsonObject("params").entrySet()) {
                    if (!entry.getValue().isJsonPrimitive() || !entry.getValue().getAsJsonPrimitive().isNumber()) {
                        throw new IllegalArgumentException(
                                "ops[" + i + "].params." + entry.getKey() + " 必须为数值");
                    }
                    params.put(entry.getKey(), entry.getValue().getAsDouble());
                }
            }
            validateOp(i, type, params);
            ops.add(new Op(type, Map.copyOf(params)));
        }
        return new FilterDefinition(name, ops);
    }

    /** op 规则单点（校验/执行共用）。 */
    public static void validateOp(int index, String type, Map<String, Double> params) {
        switch (type) {
            case "brightness" -> requireRange(index, type, params, "value", -1.0D, 1.0D);
            case "contrast" -> requireRange(index, type, params, "value", -1.0D, 1.0D);
            case "saturation" -> requireRange(index, type, params, "value", 0.0D, 2.0D);
            case "hue" -> requireRange(index, type, params, "degrees", -180.0D, 180.0D);
            case "grayscale", "invert" -> {
                // 无参数
            }
            default -> throw new IllegalArgumentException("ops[" + index + "] 未知 type：" + type
                    + "（支持 brightness/contrast/saturation/hue/grayscale/invert）");
        }
    }

    private static void requireRange(int index, String type, Map<String, Double> params, String key,
            double min, double max) {
        Double value = params.get(key);
        if (value == null) {
            throw new IllegalArgumentException("ops[" + index + "] type=" + type + " 缺少参数 " + key
                    + "（范围 [" + min + "," + max + "]，示例：{\"value\":0.0}）");
        }
        if (!Double.isFinite(value) || value < min || value > max) {
            throw new IllegalArgumentException("ops[" + index + "] type=" + type + " 参数 " + key
                    + " 越界：实际 " + value + "，范围 [" + min + "," + max + "]");
        }
    }

    private static String requireString(JsonObject object, String key) {
        if (!object.has(key) || !object.get(key).isJsonPrimitive()) {
            throw new IllegalArgumentException("缺少字符串字段：" + key);
        }
        String value = object.get(key).getAsString();
        if (value.isBlank()) {
            throw new IllegalArgumentException("字段不能为空：" + key);
        }
        return value;
    }
}
