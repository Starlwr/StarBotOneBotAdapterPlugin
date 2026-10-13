package com.starlwr.bot.adapter.onebot.message;

import com.alibaba.fastjson2.JSONObject;
import com.nulabinc.zxcvbn.Strength;
import com.nulabinc.zxcvbn.Zxcvbn;
import com.starlwr.bot.adapter.onebot.config.OneBotAdapterPluginProperties;
import com.starlwr.bot.adapter.onebot.config.OneBotSender;
import com.starlwr.bot.core.message.Sender;
import com.starlwr.bot.core.message.StarBotSenderService;
import com.starlwr.bot.core.plugin.StarBotComponent;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;

/**
 * OneBot 控制器
 */
@Slf4j
@RestController
@StarBotComponent
public class OneBotController {
    private final WebServerApplicationContext webContext;

    private final RequestMappingHandlerMapping mapping;

    private final OneBotAdapterPluginProperties properties;

    private final StarBotSenderService senderService;

    private final OneBotHttpService httpService;

    private final Zxcvbn ZXCVBN = new Zxcvbn();

    @Autowired
    public OneBotController(WebServerApplicationContext webContext, RequestMappingHandlerMapping mapping, OneBotAdapterPluginProperties properties, StarBotSenderService senderService, OneBotHttpService httpService) {
        this.webContext = webContext;
        this.mapping = mapping;
        this.properties = properties;
        this.senderService = senderService;
        this.httpService = httpService;
    }

    /**
     * 注册 OneBot 推送平台接口
     */
    @Order(-20000)
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReadyEvent() {
        Method method;
        try {
            method = getClass().getMethod("send", MessageDTO.class, HttpServletRequest.class, HttpServletResponse.class);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException("注册推送 API 异常", e);
        }

        for (OneBotSender sender : properties.getSenders()) {
            if (StringUtils.isBlank(sender.getOneBotHttpToken())) {
                log.error("推送平台 {} 未配置 OneBot HTTP Token, 请完善配置", sender.getName());
                continue;
            }

            String token = resolveToken(sender);
            if (token == null) {
                log.error("推送平台 {} 未配置推送接口 Token, 已跳过注册, 请完善配置", sender.getName());
                continue;
            }

            try {
                RequestMappingInfo info = RequestMappingInfo
                        .paths(properties.getBaseUrl() + sender.getApi())
                        .methods(RequestMethod.POST)
                        .build();
                mapping.registerMapping(info, this, method);
            } catch (Exception e) {
                log.error("推送平台 {} 注册异常", sender.getName(), e);
            }

            String path = properties.getBaseUrl() + sender.getApi();
            String url = "http://localhost:" + webContext.getWebServer().getPort() + path;
            senderService.addSender(new Sender(sender.getName(), url, token, sender.getDelay()));

            httpService.register(sender);
        }
    }

    /**
     * 发送消息到 OneBot
     * @param message 消息
     * @param request HTTP 请求
     * @param response HTTP 响应
     * @return 调用结果
     */
    public JSONObject send(@RequestBody MessageDTO message, HttpServletRequest request, HttpServletResponse response) {
        response.setStatus(HttpStatus.OK.value());

        Optional<Sender> sender = senderService.getSender(message.getPlatform());
        if (sender.isEmpty()) {
            log.warn("未知的推送平台 {}: ([{}] {}) -> {}", message.getPlatform(), message.getType().getStr(), message.getNum(), message.getContent());

            return new JSONObject()
                    .fluentPut("code", ResultCode.UNKNOWN_PLATFORM.getCode())
                    .fluentPut("message", ResultCode.UNKNOWN_PLATFORM.getMsg())
                    .fluentPut("id", null);
        }

        if (!verifyToken(sender.get().getToken(), extractBearerToken(request))) {
            log.warn("推送平台 {} 拒绝了来自 {} 的未授权请求: ([{}] {}) -> {}", message.getPlatform(), request.getRemoteAddr(), message.getType().getStr(), message.getNum(), message.getContent());

            return new JSONObject()
                    .fluentPut("code", ResultCode.UNAUTHORIZED.getCode())
                    .fluentPut("message", ResultCode.UNAUTHORIZED.getMsg())
                    .fluentPut("id", null);
        }

        return httpService.send(message);
    }

    /**
     * 解析推送接口 Token，未配置时返回 null，配置过弱时输出警告
     * @param sender 推送平台配置
     * @return 去除首尾空白后的 Token，未配置时返回 null
     */
    private String resolveToken(OneBotSender sender) {
        if (StringUtils.isBlank(sender.getToken())) {
            return null;
        }

        String token = sender.getToken().strip();
        warnIfWeakToken(sender.getName(), token);

        return token;
    }

    /**
     * 评估 Token 强度，评分过低时输出警告
     * @param senderName 推送平台名称
     * @param token Token
     */
    private void warnIfWeakToken(String senderName, String token) {
        Strength strength = ZXCVBN.measure(token);
        if (strength.getScore() < 3) {
            log.warn("推送平台 {} 配置的推送接口 Token 强度过低, 若当前部署在公网环境中, 建议修改", senderName);
        }
    }

    /**
     * 从请求中提取 Token
     * @param request HTTP 请求
     * @return Token，不存在时返回 null
     */
    private String extractBearerToken(HttpServletRequest request) {
        String prefix = "Bearer ";

        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (StringUtils.isBlank(authorization)) {
            return null;
        }

        String value = authorization.strip();
        if (value.regionMatches(true, 0, prefix, 0, prefix.length())) {
            return value.substring(prefix.length()).strip();
        }

        return value;
    }

    /**
     * 恒定时间比对 Token
     * @param expected 期望的 Token
     * @param presented 请求携带的 Token
     * @return 是否一致
     */
    private boolean verifyToken(String expected, String presented) {
        if (StringUtils.isBlank(expected) || StringUtils.isBlank(presented)) {
            return false;
        }

        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8)
        );
    }
}
