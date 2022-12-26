package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.BooleanUtil;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * @author Rhythm-2019
 * @date 2022/12/25
 * @description Redis 分布式锁
 */
public class RedisLock {

    public static final String LOCK_PREFIX = "lock:";
    public static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";
    private static DefaultRedisScript<Long> unlockScript;
    private String idPrefix;
    private String name;
    private StringRedisTemplate stringRedisTemplate;

    static {
        unlockScript = new DefaultRedisScript<>();
        unlockScript.setLocation(new ClassPathResource("redis/unlock.lua"));
        unlockScript.setResultType(Long.class);
    }
    public RedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public boolean tryLock(long expireTime, TimeUnit unit) {
        String id = ID_PREFIX + Thread.currentThread().getId();
        return BooleanUtil.isTrue(stringRedisTemplate.opsForValue().setIfAbsent(LOCK_PREFIX + this.name, id, expireTime, unit));
    }

    public void unlock() {
        //当业务超时时，可能存在其他线程获取了锁，这是如果直接解锁会导致更多的线程可以获取到锁
        // stringRedisTemplate.delete(LOCK_PREFIX + this.name);

        // 但是下面的写法没有原子性保证
        // if (stringRedisTemplate.opsForValue().get(LOCK_PREFIX + this.name) == ID_PREFIX + Thread.currentThread().getId()) {
        //     stringRedisTemplate.delete(LOCK_PREFIX + this.name);
        //

        // 所以最后要依赖 redis 提供的 lua 脚本执行保证原子性 的特性
        stringRedisTemplate.execute(unlockScript,
                Collections.singletonList(LOCK_PREFIX + this.name),
                ID_PREFIX + Thread.currentThread().getId());
    }
}
