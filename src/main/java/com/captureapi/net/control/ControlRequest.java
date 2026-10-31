package com.captureapi.net.control;

import com.google.gson.JsonObject;

/**
 * WS 控制指令信封（I4.3.1）：`{id, action, params}`。
 *
 * @param id     客户端生成的配对 id（可为空字符串）
 * @param action 指令名
 * @param params 参数对象（缺省空对象）
 */
public record ControlRequest(String id, String action, JsonObject params) {

    public ControlRequest {
        id = id == null ? "" : id;
        action = action == null ? "" : action;
        params = params == null ? new JsonObject() : params;
    }
}
