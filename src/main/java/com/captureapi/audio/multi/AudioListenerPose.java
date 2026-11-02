package com.captureapi.audio.multi;

/**
 * listener 位姿（度）。
 */
public record AudioListenerPose(double x, double y, double z, float yawDegrees,
                                float pitchDegrees, float rollDegrees) {
}
