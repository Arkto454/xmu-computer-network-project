package cn.campus.errand.student;

import java.time.Duration;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(WeChatProperties.class)
public class WeChatConfig {
    @Bean
    WeChatGateway weChatGateway(RestClient.Builder builder, WeChatProperties properties, ObjectMapper json) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(5));
        return new HttpWeChatGateway(builder.requestFactory(factory).build(), properties, json);
    }
}
