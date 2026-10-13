package com.starlwr.bot.adapter.onebot.config;

import com.starlwr.bot.core.plugin.StarBotComponent;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * StarBotOneBotAdapterPlugin 配置类
 */
@Getter
@Setter
@Configuration
@StarBotComponent
@ConfigurationProperties(prefix = "starbot.adapter.onebot")
public class OneBotAdapterPluginProperties {
    /**
     * OneBot 推送接口统一前缀
     */
    @Getter
    private String baseUrl = "/onebot";

    /**
     * OneBot 推送平台列表
     */
    @Getter
    private List<OneBotSender> senders = new ArrayList<>();

    @Getter
    private WebsocketThread websocketThread = new WebsocketThread();

    @Getter
    private Detect detect = new Detect();

    /**
     * 线程相关
     */
    @Getter
    @Setter
    public static class WebsocketThread {
        /**
         * 线程池核心线程数
         */
        private int corePoolSize = 10;

        /**
         * 线程池最大线程数
         */
        private int maxPoolSize = 100;

        /**
         * 线程池任务队列容量
         */
        private int queueCapacity = 0;

        /**
         * 非核心线程存活时间，单位：秒
         */
        private int keepAliveSeconds = 300;
    }

    /**
     * 检测相关
     */
    @Getter
    @Setter
    public static class Detect {
        /**
         * 是否启用服务可用性检测
         */
        private boolean enableDetect = false;

        /**
         * 服务可用性检测周期，单位: 秒
         */
        private int detectInterval = 300;

        /**
         * 告警邮件发送最短间隔时间，用于防止短时间内发送大量告警邮件，单位: 秒
         */
        private int alarmMailInterval = 3600;
    }
}
