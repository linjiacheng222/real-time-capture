# JPEG 编码与帧流水线基准方法（T2.5.3）

> 状态：方法已固化（2026-10-31）；实测数据归入后续《测试分计划》，MS2 验收时补报告。
> 关联：I2.5.1 JPEG 编码器、I2.5.2 编码线程归属、M7 T7.2 性能基线。

## 一、被测链路

离屏渲染（`GameRenderer#renderLevel`）→ PBO 回读（写 N 读 N-1）→ JPEG 编码（`jpeg_quality`）→ `VideoFrameBus` 最新帧发布。

## 二、分段计时

`PipelineTimings.record(renderNanos, readbackNanos, encodeNanos)`（CaptureClock 差值，红线 4）：

| 段 | 起点 | 终点 |
|---|---|---|
| render | 调用 `renderLevel` 前 | 返回后 |
| readback | 调 `PboPair.readback()` 前 | 返回后 |
| encode | 调 `EncodingPipeline.accept()` 前 | 返回后（异步模式≈入队耗时） |

`log_pipeline_timing=true` 时每 300 帧向 `latest.log` 输出一次四段均值；M7 性能基线复用同一 markers（`[CaptureAPI]`）。

## 三、预算判定

- 30fps 目标帧间隔 33.3ms；渲染线程总占用预算 **< 23ms**（70%），余量给主画面；
- 若超预算：a) `encode_async=false/true` 前后对比；b) 仍超则上呈（降分辨率/降帧率属用户决策，不自行拍板）；
- 报告按设备档位记录（集显/独显、光影关/开）。

## 四、验收用例（执行时）

1. 默认参数（1280×1024@30、质量 75）连续 60s，采集 3 组 300 帧均值；
2. 质量 50/75/95 三档 JPEG 字节大小单调递增（同场景同帧）；
3. 抽 10 帧 JPEG 解码与 RawFrame 比对，允许 ±1 LSB；行方向正确（上下不颠倒）；
4. `encode_async` 两模式内容一致性（同时间戳附近帧）与发布顺序=时间戳顺序；
5. 结论表：通过 / 超预算上呈；证据存 `docs/evidence/T2.5/`（待测试分计划创建）。

## 五、当前实现要点

- 默认渲染线程内联编码；`encode_async=true` 使用有界队列（容量 2）+ `captureapi-encoder` 守护线程，队列满丢最旧；
- ImageIO JPEG（`ImageWriter` + `ImageWriteParam` 质量），按线程复用 writer；setup 期预热一次；
- 无中间文件落盘；`JpegFrame` 携带 `timestampNanos` 供消费方判新鲜度。
