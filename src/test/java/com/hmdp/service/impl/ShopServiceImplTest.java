package com.hmdp.service.impl;

import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import com.hmdp.utils.RedisConstants;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.junit4.SpringRunner;

import javax.annotation.Resource;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * @author Rhythm-2019
 * @date 2022/12/22
 */
@SpringBootTest
@RunWith(SpringRunner.class)
public class ShopServiceImplTest {

    @Resource
    private IShopService shopService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Test
    public void saveShopToRedis() {
        shopService.saveShopToRedis(1L, 10L);
    }


    @Test
    public void loadGeoToRedis() {
        Map<Long, List<Shop>> typeMap = shopService.list()
                .stream()
                .collect(Collectors.groupingBy(Shop::getTypeId));

        typeMap.forEach((typeId, shopList) -> {
            List<RedisGeoCommands.GeoLocation<String>> geoLocationList = shopList.stream()
                    .map(shop -> new RedisGeoCommands.GeoLocation<String>(shop.getId().toString(), new Point(shop.getX(), shop.getY())))
                    .collect(Collectors.toList());

            stringRedisTemplate.opsForGeo()
                    .add(RedisConstants.SHOP_GEO_KEY + typeId, geoLocationList);
        });
    }
}