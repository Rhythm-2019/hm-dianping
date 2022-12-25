package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * @author Rhythm-2019
 * @date 2022/12/23
 * @description Redis ID生成
 */
@Component
public class RedisIdWorker {

    public static final long BEGIN_TIME_UTC_TS = LocalDateTime.of(2022, 1, 1, 0, 0, 0).toEpochSecond(ZoneOffset.UTC);

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public long nextId(String key) {
        LocalDateTime now = LocalDateTime.now();
        return ((now.toEpochSecond(ZoneOffset.UTC) - BEGIN_TIME_UTC_TS) << 31 ) |
                stringRedisTemplate.opsForValue().increment("icr:" + now.format(DateTimeFormatter.ofPattern("yyyyMMdd")) + key);
    }
}
