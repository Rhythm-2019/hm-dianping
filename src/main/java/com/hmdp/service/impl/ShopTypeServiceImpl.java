package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryTypeList() {
        // 读取缓存
        Set<String> shopStrSet = stringRedisTemplate.opsForZSet().rangeByScore(RedisConstants.SHOP_TYPE_LIST_KEY, 0, -1);
        if (shopStrSet != null && !shopStrSet.isEmpty()) {
            return Result.ok(shopStrSet.stream()
                    .map(shopStr -> JSONUtil.toBean(shopStr, ShopType.class))
                    .collect(Collectors.toList()));
        }

        List<ShopType> shopTypeList = query().orderByAsc("sort").list();
        Set<ZSetOperations.TypedTuple<String>> typedTupleSet = shopTypeList.stream()
                .map(shopType -> new DefaultTypedTuple<>(JSONUtil.toJsonStr(shopType), Double.valueOf(shopType.getSort())))
                .collect(Collectors.toSet());
        stringRedisTemplate.opsForZSet().add(RedisConstants.SHOP_TYPE_LIST_KEY, typedTupleSet);
        stringRedisTemplate.expire(RedisConstants.SHOP_TYPE_LIST_KEY, RedisConstants.SHOP_TYPE_LIST_TTL, TimeUnit.MINUTES);

        return Result.ok(shopTypeList);
    }
}
