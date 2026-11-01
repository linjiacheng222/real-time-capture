package com.captureapi.core.constant;

/**
 * 网络/协议层固定常数（红线 1 白名单登记）。
 *
 * <p>HTTP 状态码与 WebSocket 关闭码属于协议规范，不是可调业务参数，集中登记避免散落魔法数字；
 * 变更须同时核对 RFC 6455 / HTTP 语义。</p>
 */
public final class ProtocolConstants {

    /** HTTP 200 OK。 */
    public static final int HTTP_OK = 200;
    /** HTTP 204 No Content。 */
    public static final int HTTP_NO_CONTENT = 204;
    /** HTTP 400 Bad Request。 */
    public static final int HTTP_BAD_REQUEST = 400;
    /** HTTP 401 Unauthorized。 */
    public static final int HTTP_UNAUTHORIZED = 401;
    /** HTTP 404 Not Found。 */
    public static final int HTTP_NOT_FOUND = 404;
    /** HTTP 405 Method Not Allowed。 */
    public static final int HTTP_METHOD_NOT_ALLOWED = 405;
    /** HTTP 429 Too Many Requests。 */
    public static final int HTTP_TOO_MANY_REQUESTS = 429;
    /** HTTP 500 Internal Server Error。 */
    public static final int HTTP_INTERNAL_ERROR = 500;
    /** HTTP 503 Service Unavailable。 */
    public static final int HTTP_SERVICE_UNAVAILABLE = 503;

    /** WebSocket 1001 Going Away。 */
    public static final int WS_CLOSE_GOING_AWAY = 1001;
    /** WebSocket 1003 Unsupported Data。 */
    public static final int WS_CLOSE_UNSUPPORTED = 1003;
    /** WebSocket 1009 Message Too Big。 */
    public static final int WS_CLOSE_TOO_BIG = 1009;
    /** WebSocket 1011 Internal Error。 */
    public static final int WS_CLOSE_INTERNAL_ERROR = 1011;
    /** WebSocket 1013 Try Again Later。 */
    public static final int WS_CLOSE_TRY_AGAIN = 1013;

    /** WebSocket 短载荷长度上限（<=125 用 7bit 长度）。 */
    public static final int WS_PAYLOAD_SMALL_MAX = 125;
    /** WebSocket 16bit 扩展长度上限（<=65535）。 */
    public static final int WS_PAYLOAD_MEDIUM_MAX = 65535;
    /** WebSocket 16bit 扩展长度标记。 */
    public static final int WS_LENGTH_MARKER_16 = 126;
    /** WebSocket 64bit 扩展长度标记。 */
    public static final int WS_LENGTH_MARKER_64 = 127;
    /** WebSocket 64bit 长度字段字节数。 */
    public static final int WS_LENGTH_64_BYTES = 8;
    /** WebSocket 关闭 reason 最大字节数（协议限制 123）。 */
    public static final int WS_CLOSE_REASON_MAX = 123;
    /** TCP 端口上限。 */
    public static final int MAX_TCP_PORT = 65535;

    private ProtocolConstants() {
        throw new AssertionError("no instances");
    }
}
