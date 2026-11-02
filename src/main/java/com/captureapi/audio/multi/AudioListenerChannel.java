package com.captureapi.audio.multi;

/**
 * 一个 listener 通道（player/camera/virtual 的通用抽象，Phase 1）。
 */
public final class AudioListenerChannel {

    private final int id;
    private final AudioChannelKind kind;
    private final int cameraId;
    private final AudioPcmRing ring;
    private final AudioContextWorker worker;
    private volatile boolean active;

    public AudioListenerChannel(int id, AudioChannelKind kind, int cameraId, AudioPcmRing ring,
                                AudioContextWorker worker) {
        this.id = id;
        this.kind = kind;
        this.cameraId = cameraId;
        this.ring = ring;
        this.worker = worker;
    }

    public int id() {
        return id;
    }

    public AudioChannelKind kind() {
        return kind;
    }

    public int cameraId() {
        return cameraId;
    }

    public AudioPcmRing ring() {
        return ring;
    }

    public AudioContextWorker worker() {
        return worker;
    }

    public boolean active() {
        return active && worker.isRunning();
    }

    /** 启动通道（testFrequency<=0 时不建合成源）。 */
    public boolean start(double testFrequency, double testX) {
        boolean started = worker.start(testFrequency, testX);
        active = started;
        return started;
    }

    public void setListenerPose(AudioListenerPose pose) {
        worker.setListenerPose(pose);
    }

    public void stop() {
        active = false;
        worker.stop();
    }

    public AudioChannelInfo info() {
        return new AudioChannelInfo(id, kind, cameraId, active(),
                worker.renderedPolls(), ring.droppedPolls(), worker.errors());
    }
}
