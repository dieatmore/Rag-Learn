package org.example.raglearn.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {
    // spring-ai-alibaba-starter-dashscope，需要注入RestClient
    @Bean
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder();
    }
}