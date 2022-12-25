package com.hmdp.utils;

import com.sun.org.apache.xalan.internal.lib.ExsltBase;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import javax.annotation.Resource;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @author Rhythm-2019
 * @date 2022/12/23
 * @description
 */
@SpringBootTest
@RunWith(SpringRunner.class)
public class RedisIdWorkerTest {

    @Resource
    private RedisIdWorker redisIdWorker;

    @Test
    public void testNextId() throws InterruptedException {
        ExecutorService executorService = Executors.newFixedThreadPool(5);

        CountDownLatch countDownLatch = new CountDownLatch(2000);
        for (int i = 0; i < 2000; i++) {
            executorService.submit(() -> {
                System.out.println(redisIdWorker.nextId("test"));
                countDownLatch.countDown();
            });
        }

        countDownLatch.await();
    }

}