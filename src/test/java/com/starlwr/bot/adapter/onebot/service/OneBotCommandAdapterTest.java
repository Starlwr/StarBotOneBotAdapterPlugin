package com.starlwr.bot.adapter.onebot.service;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.adapter.onebot.http.OneBotHttpAdapter;
import com.starlwr.bot.adapter.onebot.model.OneBotSender;
import com.starlwr.bot.core.enums.GroupRole;
import com.starlwr.bot.core.model.CommandMessage;
import com.starlwr.bot.core.sender.StarBotMessageSender;
import com.starlwr.bot.core.service.StarBotCommandService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * OneBot 命令适配器测试
 * <p>
 * 使用 OneBot 数组消息段构造群聊和私聊事件，覆盖消息路由、命令消息归一化、提及识别、
 * 回复识别、图片参数以及无效消息段忽略等协议边界，不发起真实 OneBot HTTP 请求。
 */
class OneBotCommandAdapterTest {
    private StarBotCommandService commands;

    private OneBotHttpAdapter http;

    private OneBotSender sender;

    private OneBotCommandAdapter adapter;

    /**
     * 创建命令服务、HTTP 适配器和推送平台测试替身
     *
     */
    @BeforeEach
    void setup() {
        commands = mock(StarBotCommandService.class);
        http = mock(OneBotHttpAdapter.class);
        sender = new OneBotSender();
        sender.setName("qq-a");
        adapter = new OneBotCommandAdapter(commands, mock(StarBotMessageSender.class), http);
    }

    /**
     * 创建标准 OneBot 群消息事件
     *
     * @param content 消息段数组
     * @return OneBot 消息事件
     */
    private JSONObject message(JSONArray content) {
        return new JSONObject().fluentPut("post_type", "message").fluentPut("message_type", "group")
                .fluentPut("self_id", 1L).fluentPut("user_id", 2L).fluentPut("group_id", 100L)
                .fluentPut("group_name", "测试群").fluentPut("time", 1790667518L)
                .fluentPut("message_id", 300).fluentPut("raw_message", "raw").fluentPut("message", content)
                .fluentPut("sender", new JSONObject().fluentPut("nickname", "小明")
                        .fluentPut("card", "群里的小明").fluentPut("role", "admin"));
    }

    /**
     * 创建只包含一个数据字段的消息段
     *
     * @param type 消息段类型
     * @param field 数据字段名
     * @param value 数据字段值
     * @return OneBot 消息段
     */
    private JSONObject segment(String type, String field, String value) {
        return new JSONObject().fluentPut("type", type).fluentPut("data", new JSONObject().fluentPut(field, value));
    }

    /**
     * 按给定顺序创建消息段数组
     *
     * @param segments 消息段
     * @return 消息段数组
     */
    private JSONArray segments(JSONObject... segments) {
        JSONArray result = new JSONArray();
        result.addAll(Arrays.asList(segments));
        return result;
    }

    /**
     * 验证提及机器人、命令字段、发送者元数据和完整原始事件能够正确归一化
     */
    @Test
    void normalizesMentionCommandAndSenderMetadata() {
        JSONArray parts = new JSONArray();
        parts.add(segment("at", "qq", "1"));
        parts.add(segment("text", "text", "你好 参数"));
        JSONObject event = message(parts);
        var commandMessage = normalize(sender, event).orElseThrow();
        assertTrue(commandMessage.isMentioned());
        assertEquals("{at=1}你好 参数", commandMessage.getContent().strip());
        assertEquals("小明", commandMessage.getNickname());
        assertEquals("测试群", commandMessage.getGroupName());
        assertEquals("群里的小明", commandMessage.getGroupNickName());
        assertEquals(GroupRole.ADMIN, commandMessage.getRole());
        assertEquals(1L, commandMessage.getBotId());
        assertEquals(100L, commandMessage.getTargetId());
        assertEquals(Instant.ofEpochSecond(1790667518L), commandMessage.getTimestamp());
        assertEquals("300", commandMessage.getMessageId());
        assertEquals(event, commandMessage.getRawMessage());
        assertEquals("qq-a", commandMessage.getPlatform());

        event.remove("time");
        assertNull(normalize(sender, event).orElseThrow().getTimestamp());
    }

    /**
     * 验证网络图片仅从 url 字段转换为 Core 占位符，不可用来源直接忽略
     */
    @Test
    void imageSegmentsPreserveUsableSources() {
        JSONArray parts = new JSONArray();
        parts.add(segment("text", "text", "识图"));
        JSONObject image = segment("image", "file", "remote-file-id");
        image.getJSONObject("data").put("url", "https://example.com/image.png?a=1&b=2");
        parts.add(image);
        var commandMessage = normalize(sender, message(parts)).orElseThrow();
        assertEquals("识图{image_url=https://example.com/image.png?a=1&b=2}", commandMessage.getContent().strip());
        assertEquals("", normalize(sender, message(segments(
                segment("image", "file", "https://example.com/a.png")))).orElseThrow().getContent());
        assertEquals("{image_url=http://example.com/a.png}", normalize(sender, message(segments(
                segment("image", "url", "http://example.com/a.png")))).orElseThrow().getContent().strip());
        assertEquals("", normalize(sender, message(segments(
                segment("image", "file", "base64://YWJj")))).orElseThrow().getContent());
        assertEquals("", normalize(sender, message(segments(
                segment("image", "file", "file:///remote/image.png")))).orElseThrow().getContent());
        assertEquals("", normalize(sender, message(segments(
                segment("image", "url", "https://example.com/{image}.png")))).orElseThrow().getContent());
    }

    /**
     * 验证仅当被回复消息由机器人发送时才标记为回复机器人
     */
    @Test
    void replyMustActuallyBelongToBot() {
        JSONArray content = segments(segment("reply", "id", "3"), segment("text", "text", "你好"));
        when(http.getMsg(eq(sender), any())).thenReturn(new JSONObject().fluentPut("sender", new JSONObject().fluentPut("user_id", 1L)));
        var commandMessage = normalize(sender, message(content)).orElseThrow();
        assertTrue(commandMessage.isReplyToBot());
        when(http.getMsg(eq(sender), any())).thenReturn(new JSONObject().fluentPut("sender", new JSONObject().fluentPut("user_id", 9L)));
        assertFalse(normalize(sender, message(content)).orElseThrow().isReplyToBot());
        when(http.getMsg(eq(sender), any())).thenThrow(new IllegalStateException("不可查询"));
        assertFalse(normalize(sender, message(content)).orElseThrow().isReplyToBot());
    }

    /**
     * 验证无效图片和未知消息段直接忽略，提及占位符保留在标准化内容中
     */
    @Test
    void invalidAndUnknownSegmentsAreIgnored() {
        var image = normalize(sender, message(segments(segment("text", "text", "你"),
                segment("image", "file", "x"), segment("unknown", "value", "ignored"),
                segment("text", "text", "好")))).orElseThrow();
        assertEquals("你好", image.getContent());
        var mention = normalize(sender, message(segments(segment("text", "text", "你"),
                segment("at", "qq", "1"), segment("text", "text", "好")))).orElseThrow();
        assertEquals("你{at=1}好", mention.getContent());
    }

    /**
     * 验证私聊不会继承群身份，昵称缺失时使用空串且空白昵称保持不变
     */
    @Test
    void privateRoleNeverGrantsGroupAdminAndPreservesNickname() {
        var json = message(segments(segment("text", "text", "你好")));
        json.put("message_type", "private");
        json.getJSONObject("sender").remove("nickname");
        var commandMessage = normalize(sender, json).orElseThrow();
        assertEquals(GroupRole.UNKNOWN, commandMessage.getRole());
        assertEquals(2L, commandMessage.getTargetId());
        assertEquals("", commandMessage.getNickname());
        assertEquals("", commandMessage.getGroupName());
        assertEquals("", commandMessage.getGroupNickName());
        json.getJSONObject("sender").put("nickname", "   ");
        assertEquals("   ", normalize(sender, json).orElseThrow().getNickname());
    }

    /**
     * 验证不符合命令处理条件的事件返回空结果且不查询回复消息
     */
    @Test
    void invalidEventsReturnEmpty() {
        JSONArray content = segments(segment("text", "text", "你好"));
        assertTrue(normalize(sender, message(content).fluentPut("post_type", "notice")).isEmpty());
        assertTrue(normalize(sender, message(content).fluentPut("message_type", "unknown")).isEmpty());
        for (String field : new String[]{"self_id", "user_id", "group_id"}) {
            JSONObject event = message(content);
            event.remove(field);
            assertTrue(normalize(sender, event).isEmpty());
        }
        verifyNoInteractions(http);
    }

    /**
     * 验证命令消息在命令线程中由消息快照构建
     */
    @Test
    void buildsMessageOnCommandExecutorFromMessageSnapshot() {
        final Supplier<?>[] delayed = new Supplier<?>[1];
        when(commands.dispatch(any())).thenAnswer(call -> {
            delayed[0] = call.getArgument(0);
            return CompletableFuture.completedFuture(null);
        });
        var json = message(segments(segment("reply", "id", "3"), segment("text", "text", "你好")));
        adapter.accept(sender, json);
        verifyNoInteractions(http);
        json.put("message", segments(segment("text", "text", "changed")));
        when(http.getMsg(eq(sender), any())).thenReturn(new JSONObject().fluentPut("sender", new JSONObject().fluentPut("user_id", 1L)));
        @SuppressWarnings("unchecked")
        var commandMessage = ((Optional<CommandMessage>) delayed[0].get()).orElseThrow();
        assertTrue(commandMessage.isReplyToBot());
        assertEquals("你好", commandMessage.getContent().strip());
    }

    /**
     * 调用私有命令消息转换方法
     */
    private Optional<CommandMessage> normalize(OneBotSender sender, JSONObject message) {
        try {
            var method = OneBotCommandAdapter.class.getDeclaredMethod(
                    "normalize", OneBotSender.class, JSONObject.class);
            method.setAccessible(true);
            @SuppressWarnings("unchecked")
            Optional<CommandMessage> result = (Optional<CommandMessage>) method.invoke(adapter, sender, message);
            return result;
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("调用命令消息转换方法失败", e);
        }
    }
}
