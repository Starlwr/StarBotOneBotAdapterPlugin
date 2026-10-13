package com.starlwr.bot.adapter.onebot.config;

import lombok.Getter;
import lombok.Setter;

/**
 * OneBot 推送平台信息
 */
@Getter
@Setter
public class OneBotSender {
    /**
     * 名称
     */
    private String name;

    /**
     * 开放推送接口地址，用于设置多机器人推送，例如：/send
     */
    private String api;

    /**
     * 推送接口 Token
     */
    private String token;

    /**
     * 是否启用 Websocket
     */
    private boolean websocket;

    /**
     * OneBot 地址
     */
    private String oneBotAddress;

    /**
     * OneBot HTTP 端口号
     */
    private int oneBotHttpPort;

    /**
     * OneBot Websocket 端口号
     */
    private int oneBotWebsocketPort;

    /**
     * OneBot HTTP Token
     */
    private String oneBotHttpToken;

    /**
     * OneBot Websocket Token
     */
    private String oneBotWebsocketToken;

    /**
     * 消息发送间隔时间，单位：毫秒
     */
    private int delay = 0;

    /**
     * 请求信息 Debug 日志最大输出长度，设置为 0 不限制长度
     */
    private int debugLogMaxLength = 1000;
}
