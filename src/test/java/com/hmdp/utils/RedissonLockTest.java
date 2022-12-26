package com.hmdp.utils;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.omg.CORBA.PUBLIC_MEMBER;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import javax.annotation.Resource;

/**
 * @author Rhythm-2019
 * @date 2022/12/26
 * @description
 */
@SpringBootTest
@RunWith(SpringRunner.class)
public class RedissonLockTest {

    @Resource
    private RedissonClient redissonClient;

    @Test
    public void test01() {
        RLock lock = redissonClient.getLock("demo");
        try {
            lock.lock();

            System.out.println("aaa");
        } finally {
            lock.unlock();
        }
    }

}
