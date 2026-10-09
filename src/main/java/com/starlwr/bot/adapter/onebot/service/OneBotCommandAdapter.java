package com.starlwr.bot.adapter.onebot.service;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.adapter.onebot.http.OneBotHttpAdapter;
import com.starlwr.bot.adapter.onebot.model.OneBotSender;
import com.starlwr.bot.core.enums.GroupRole;
import com.starlwr.bot.core.enums.PushTargetType;
import com.starlwr.bot.core.model.CommandMessage;
import com.starlwr.bot.core.plugin.StarBotComponent;
import com.starlwr.bot.core.sender.StarBotMessageSender;
import com.starlwr.bot.core.service.StarBotCommandService;
import com.starlwr.bot.core.util.StringUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * OneBot 命令适配器
 */
@Slf4j
@StarBotComponent
public class OneBotCommandAdapter {
    private final StarBotCommandService service;

    private final StarBotMessageSender messageSender;

    private final OneBotHttpAdapter http;

    @Autowired
    public OneBotCommandAdapter(StarBotCommandService service, StarBotMessageSender messageSender, OneBotHttpAdapter http) {
        this.service = service;
        this.messageSender = messageSender;
        this.http = http;
    }

    /**
     * 向命令服务提交消息
     * @param sender OneBot 推送平台
     * @param message 原始消息事件
     * @return 异步执行结果
     */
    public CompletableFuture<Void> accept(OneBotSender sender, JSONObject message) {
        String snapshot = message.toJSONString();
        return service.dispatch(() -> normalize(sender, JSONObject.parseObject(snapshot)));
    }

    /**
     * 从原始消息事件尝试构建命令消息
     * @param sender OneBot 推送平台
     * @param message 原始消息事件
     * @return 命令消息
     */
    private Optional<CommandMessage> normalize(OneBotSender sender, JSONObject message) {
        if (!"message".equals(message.getString("post_type"))) {
            return Optional.empty();
        }

        PushTargetType type = switch (Objects.requireNonNullElse(message.getString("message_type"), "")) {
            case "private" -> PushTargetType.FRIEND;
            case "group" -> PushTargetType.GROUP;
            default -> PushTargetType.UNKNOWN;
        };
        if (type == PushTargetType.UNKNOWN) {
            return Optional.empty();
        }

        Long botId = message.getLong("self_id");
        Long groupId = message.getLong("group_id");
        Long senderId = message.getLong("user_id");
        Long time = message.getLong("time");
        Long targetId = type == PushTargetType.GROUP ? groupId : senderId;
        if (botId == null || senderId == null || targetId == null) {
            return Optional.empty();
        }

        Object messageValue = message.get("message");
        JSONArray segments = messageValue instanceof JSONArray array ? array : new JSONArray();
        StringBuilder content = new StringBuilder();
        boolean mentioned = false;
        boolean replyToBot = false;

        for (Object value : segments) {
            if (!(value instanceof JSONObject segment)) {
                continue;
            }

            JSONObject data = segment.getJSONObject("data");
            if (data == null) {
                continue;
            }

            switch (Objects.requireNonNullElse(segment.getString("type"), "")) {
                case "text" -> content.append(Objects.requireNonNullElse(data.getString("text"), ""));
                case "image" -> {
                    String source = data.getString("url");
                    if (StringUtil.isBlank(source) || source.contains("{") || source.contains("}")) {
                        continue;
                    }

                    if (source.startsWith("http")) {
                        content.append("{image_url=").append(source).append("}");
                    }
                }
                case "at" -> {
                    String qq = data.getString("qq");
                    if (StringUtil.isBlank(qq)) {
                        continue;
                    }

                    content.append("{at=").append(qq).append("}");
                    if (botId.toString().equals(qq)) {
                        mentioned = true;
                    }
                }
                case "reply" -> {
                    String id = data.getString("id");
                    if (StringUtil.isBlank(id)) {
                        continue;
                    }

                    if (!replyToBot) {
                        try {
                            JSONObject original = http.getMsg(sender, new JSONObject().fluentPut("message_id", id));
                            JSONObject author = original == null ? null : original.getJSONObject("sender");
                            replyToBot = author != null && Objects.equals(botId, author.getLong("user_id"));
                        } catch (Exception e) {
                            log.error("查询 {} 回复的源消息 {} 异常", sender.getName(), id, e);
                        }
                    }
                }
            }
        }

        JSONObject author = message.getJSONObject("sender");
        String roleText = author == null ? "" : Objects.requireNonNullElse(author.getString("role"), "");
        GroupRole role = type != PushTargetType.GROUP ? GroupRole.UNKNOWN
                : switch (roleText) {
                    case "owner" -> GroupRole.OWNER;
                    case "admin" -> GroupRole.ADMIN;
                    case "member" -> GroupRole.MEMBER;
                    default -> GroupRole.UNKNOWN;
                };

        return Optional.of(CommandMessage.builder()
                .platform(sender.getName())
                .botId(botId)
                .type(type)
                .groupId(groupId)
                .groupName(type == PushTargetType.GROUP ? message.getString("group_name") : null)
                .senderId(senderId)
                .nickname(author == null ? null : author.getString("nickname"))
                .groupNickName(type == PushTargetType.GROUP && author != null ? author.getString("card") : null)
                .role(role)
                .messageId(message.getString("message_id"))
                .content(content.toString())
                .mentioned(mentioned)
                .replyToBot(replyToBot)
                .timestamp(time == null ? null : Instant.ofEpochSecond(time))
                .rawMessage(message)
                .messageSender(messageSender)
                .build()
        );
    }
}
