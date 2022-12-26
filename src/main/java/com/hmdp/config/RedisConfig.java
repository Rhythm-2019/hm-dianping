package com.hmdp.config;


import cn.hutool.core.util.StrUtil;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedisConfig {

    @Value("${spring.redis.host}")
    private String host;
    @Value("${spring.redis.port}")
    private int port;
    @Value("${spring.redis.password}")
    private String password;

    @Bean
    public RedissonClient redissonClient(){
        Config config = new Config();
        SingleServerConfig singleServerConfig = config.useSingleServer().setAddress(String.format("redis://%s:%d", this.host, this.port));
        if (!StrUtil.isBlank(this.password)) {
            singleServerConfig.setPassword(this.password);
        }
        return Redisson.create(config);
    }
}