package com.starlwr.bot.adapter.onebot.api;

import com.starlwr.bot.core.plugin.StarBotComponent;
import com.starlwr.bot.core.support.http.HttpService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;

import java.lang.reflect.Proxy;

/**
 * OneBot HTTP 服务注册器
 */
@Slf4j
@StarBotComponent
public class OneBotHttpAdapterRegistrar {
    @Bean
    public OneBotHttpAdapterProxy oneBotHttpAdapterProxy(HttpService http) {
        return new OneBotHttpAdapterProxy(http);
    }

    @Bean
    public OneBotHttpAdapter oneBotHttpAdapter(OneBotHttpAdapterProxy proxy) {
        return (OneBotHttpAdapter) Proxy.newProxyInstance(
                OneBotHttpAdapter.class.getClassLoader(),
                new Class[]{OneBotHttpAdapter.class},
                proxy
        );
    }
}
