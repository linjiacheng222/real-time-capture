package com.captureapi.net.control;

/** 控制指令错误码（I4.3.3，契约冻结候选；只增不改语义）。 */
public final class ControlErrorCode {

    public static final String E_UNKNOWN_ACTION = "E_UNKNOWN_ACTION";
    public static final String E_INVALID_JSON = "E_INVALID_JSON";
    public static final String E_MISSING_PARAM = "E_MISSING_PARAM";
    public static final String E_PARAM_RANGE = "E_PARAM_RANGE";
    public static final String E_NOT_FOUND = "E_NOT_FOUND";
    public static final String E_RATE_LIMIT = "E_RATE_LIMIT";
    public static final String E_NO_PERMISSION = "E_NO_PERMISSION";
    public static final String E_INTERNAL = "E_INTERNAL";
    public static final String E_TEST_COMMANDS_DISABLED = "E_TEST_COMMANDS_DISABLED";

    private ControlErrorCode() {
        throw new AssertionError("no instances");
    }
}
