package com.hmdp.service.impl;

import com.hmdp.service.IShopService;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import javax.annotation.Resource;

/**
 * @author Rhythm-2019
 * @date 2022/12/22
 */
@SpringBootTest
@RunWith(SpringRunner.class)
public class ShopServiceImplTest {

    @Resource
    private IShopService shopService;

    @Test
    public void saveShopToRedis() {
        shopService.saveShopToRedis(1L, 10L);
    }

}