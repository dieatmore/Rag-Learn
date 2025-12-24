package org.example.raglearn.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@Slf4j
public class ChatClientConfig {

    @Bean
    public ChatClient getChatClient(ChatClient.Builder builder) {
        return builder.defaultSystem("你是一个系统功能问答AI助手")
                .build();
    }
}
