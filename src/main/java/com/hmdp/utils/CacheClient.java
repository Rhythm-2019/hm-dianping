package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * @author Rhythm-2019
 * @date 2022/12/23
 */
@Slf4j
@Component
public class CacheClient {

    // TODO 这里最好自己创建线程池
    public static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newSingleThreadExecutor();

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 将任意Java对象序列化为json并存储在string类型的key中，并且可以设置TTL过期时间
     * @param key 键
     * @param data 值
     * @param expire TTL
     * @param unit TTL 单位
     * @param <T> 数据类型
     */
    public <T> void set(String key, T data, Long expire, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(data), expire, unit);
    }

    /**
     * 将任意Java对象序列化为json并存储在string类型的key中，并且可以设置逻辑过期时间，用于处理缓存击穿问题
     * @param key 键
     * @param data 值
     * @param logicalExpire TTL
     * @param unit TTL 单位
     * @param <T> 数据类型
     */
    public <T> void setWithLogicalExpire(String key, T data, Long logicalExpire, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(new RedisDataWithExpireTime(
                data, LocalDateTime.now().plusSeconds(unit.toSeconds(logicalExpire)
        ))));
    }

    /**
     * 根据指定的key查询缓存，并反序列化为指定类型，利用缓存空值的方式解决缓存穿透问题
     * @param keyPrefix key 前缀
     * @param id 数据唯一编号
     * @param type  类型
     * @param dbFallback 数据库查询方法
     * @param expire 有效时间
     * @param unit 单位
     * @return 数据
     * @param <T> 数据类型
     * @param <ID> 编号类型
     */
    public <ID, T> T getWithPassThought(String keyPrefix, ID id, Class<T> type,
                                        Function<ID, T> dbFallback, Long expire, TimeUnit unit) {
        String dataStr = stringRedisTemplate.opsForValue().get(keyPrefix + id);
        if (StrUtil.isNotBlank(dataStr)) {
            if (RedisConstants.NO_EXIST_VALUE.equals(dataStr)) {
                return null;
            }
            return JSONUtil.toBean(dataStr, type);
        }

        T data = dbFallback.apply(id);
        this.set(keyPrefix + id , data, expire, unit);

        return data;
    }

    /**
     * 根据指定的key查询缓存，并反序列化为指定类型，需要利用逻辑过期解决缓存击穿问题
     * @param keyPrefix key 前缀
     * @param id 数据唯一编号
     * @param type  类型
     * @param dbFallback 数据库查询方法
     * @param expire 有效时间
     * @param unit 单位
     * @return 数据
     * @param <T> 数据类型
     * @param <ID> 编号类型
     */
    public  <ID, T> T getWithLogicalExpire(String keyPrefix, ID id, Class<T> type,
                                           Function<ID, T> dbFallback, Long expire, TimeUnit unit) {
        String shopStr = stringRedisTemplate.opsForValue().get(keyPrefix + id);
        if (StrUtil.isBlank(shopStr)) {
            throw new RuntimeException("数据未预热");
        }
        RedisDataWithExpireTime redisData = JSONUtil.toBean(shopStr, RedisDataWithExpireTime.class);
        T data = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        // 判断是否过期
        if (redisData.getExpireTime().isAfter(LocalDateTime.now())) {
            // 还没过期
            return data;
        }
        // 已经过期，尝试获取锁
        if (!tryLock(id)) {
            // 没有获取到锁，说明其他线程正在重建
            return data;
        }
        // 创建线程并进行重建
        CACHE_REBUILD_EXECUTOR.submit(() -> {
            log.info("just one thread go to here");
            this.setWithLogicalExpire(keyPrefix + id, dbFallback.apply(id), expire, unit);
            this.unLock(id);
        });

        return data;
    }

    private <ID> boolean tryLock(ID id) {
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id.toString();
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(lockKey, RedisConstants.LOCK_VALUE, RedisConstants.LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private <ID> void unLock(ID id) {
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id.toString();
        stringRedisTemplate.delete(lockKey);
    }
    @Data
    @AllArgsConstructor
    private static class RedisDataWithExpireTime {
        private Object data;
        private LocalDateTime expireTime;
    }
}
