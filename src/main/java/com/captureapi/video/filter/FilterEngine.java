package com.captureapi.video.filter;

import com.captureapi.video.frame.RawFrame;

/**
 * CPU 滤镜执行引擎（T8.1 / I8.1.2/I8.1.3）。
 *
 * <p>RGBA8 逐像素按 op 顺序处理；灰度采用 Rec.709 加权（0.2126/0.7152/0.0722）；
 * 输出为新 RawFrame（时间戳/宽高不变）。</p>
 */
public final class FilterEngine {

    /** 8bit 颜色通道上限。 */
    private static final int COLOR_MAX = 255;

    private FilterEngine() {
        throw new AssertionError("no instances");
    }

    /**
     * 应用滤镜。
     *
     * @param frame 原始帧
     * @param def   滤镜定义
     * @return 处理后帧
     */
    public static RawFrame apply(RawFrame frame, FilterDefinition def) {
        byte[] src = frame.rgba();
        byte[] out = src.clone();
        int width = frame.width();
        int height = frame.height();
        for (int y = 0; y < height; y++) {
            int rowOffset = y * width * 4;
            for (int x = 0; x < width; x++) {
                int i = rowOffset + x * 4;
                double r = src[i] & 0xFF;
                double g = src[i + 1] & 0xFF;
                double b = src[i + 2] & 0xFF;
                for (FilterDefinition.Op op : def.ops()) {
                    switch (op.type()) {
                        case "brightness" -> {
                            double delta = op.params().get("value") * 255.0D;
                            r += delta;
                            g += delta;
                            b += delta;
                        }
                        case "contrast" -> {
                            double factor = 1.0D + op.params().get("value");
                            r = (r - 128.0D) * factor + 128.0D;
                            g = (g - 128.0D) * factor + 128.0D;
                            b = (b - 128.0D) * factor + 128.0D;
                        }
                        case "saturation" -> {
                            double gray = 0.2126D * r + 0.7152D * g + 0.0722D * b;
                            double factor = op.params().get("value");
                            r = gray + (r - gray) * factor;
                            g = gray + (g - gray) * factor;
                            b = gray + (b - gray) * factor;
                        }
                        case "hue" -> {
                            double[] hsv = rgbToHsv(clamp01(r / 255.0D), clamp01(g / 255.0D), clamp01(b / 255.0D));
                            hsv[0] = (hsv[0] + op.params().get("degrees") / 360.0D) % 1.0D;
                            if (hsv[0] < 0.0D) {
                                hsv[0] += 1.0D;
                            }
                            double[] rgb = hsvToRgb(hsv[0], hsv[1], hsv[2]);
                            r = rgb[0] * 255.0D;
                            g = rgb[1] * 255.0D;
                            b = rgb[2] * 255.0D;
                        }
                        case "grayscale" -> {
                            double gray = 0.2126D * r + 0.7152D * g + 0.0722D * b;
                            r = gray;
                            g = gray;
                            b = gray;
                        }
                        case "invert" -> {
                            r = 255.0D - r;
                            g = 255.0D - g;
                            b = 255.0D - b;
                        }
                        default -> throw new IllegalStateException("未知滤镜 op：" + op.type());
                    }
                }
                out[i] = (byte) clampByte(r);
                out[i + 1] = (byte) clampByte(g);
                out[i + 2] = (byte) clampByte(b);
                // alpha 保持不变
            }
        }
        return new RawFrame(out, width, height, frame.timestampNanos());
    }

    private static int clampByte(double value) {
        if (value <= 0.0D) {
            return 0;
        }
        if (value >= COLOR_MAX) {
            return COLOR_MAX;
        }
        return (int) Math.round(value);
    }

    private static double clamp01(double value) {
        return Math.max(0.0D, Math.min(1.0D, value));
    }

    private static double[] rgbToHsv(double r, double g, double b) {
        double max = Math.max(r, Math.max(g, b));
        double min = Math.min(r, Math.min(g, b));
        double delta = max - min;
        double h = 0.0D;
        if (delta > 1.0E-9D) {
            if (max == r) {
                h = ((g - b) / delta) % 6.0D;
            } else if (max == g) {
                h = (b - r) / delta + 2.0D;
            } else {
                h = (r - g) / delta + 4.0D;
            }
            h /= 6.0D;
            if (h < 0.0D) {
                h += 1.0D;
            }
        }
        double s = max <= 1.0E-9D ? 0.0D : delta / max;
        return new double[] {h, s, max};
    }

    private static double[] hsvToRgb(double h, double s, double v) {
        double c = v * s;
        double x = c * (1.0D - Math.abs((h * 6.0D) % 2.0D - 1.0D));
        double m = v - c;
        double r;
        double g;
        double b;
        double sector = h * 6.0D;
        if (sector < 1.0D) {
            r = c;
            g = x;
            b = 0.0D;
        } else if (sector < 2.0D) {
            r = x;
            g = c;
            b = 0.0D;
        } else if (sector < 3.0D) {
            r = 0.0D;
            g = c;
            b = x;
        } else if (sector < 4.0D) {
            r = 0.0D;
            g = x;
            b = c;
        } else if (sector < 5.0D) {
            r = x;
            g = 0.0D;
            b = c;
        } else {
            r = c;
            g = 0.0D;
            b = x;
        }
        return new double[] {r + m, g + m, b + m};
    }
}
