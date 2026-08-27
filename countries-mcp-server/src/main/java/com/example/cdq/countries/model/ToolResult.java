package com.example.cdq.countries.model;

public record ToolResult<T>(Status status, T data, String errorCode, String message) {

    public enum Status { OK, ERROR }

    public static <T> ToolResult<T> ok(T data) {
        return new ToolResult<>(Status.OK, data, null, null);
    }

    public static <T> ToolResult<T> error(String errorCode, String message) {
        return new ToolResult<>(Status.ERROR, null, errorCode, message);
    }
}
