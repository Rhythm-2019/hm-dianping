package com.hmdp.service;

import com.hmdp.entity.Shop;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IShopService extends IService<Shop> {

    Shop queryById(Long id);

    boolean updateById(Shop shop);

    boolean saveShopToRedis(Long id, Long expireSeconds);

    List<Shop> queryShopByType(Integer typeId, Integer current, Double x, Double y);
}
