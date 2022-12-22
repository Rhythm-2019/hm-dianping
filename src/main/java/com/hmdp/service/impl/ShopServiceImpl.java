package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @desciption: 主要体现数据更新策略、缓存穿透、雪崩、击穿等问题
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Shop queryById(Long id) {
        // 缓存穿透
        //  return queryByIdWithPassThrough(id);

        // 缓存击穿：在高并发下，部分 key 的重建时间比较长，导致该 key 过期时大量请求同时对 key 进行重建，导致性能相抵
        // 解救方案：1. 互斥锁：使用本地互斥锁或者 Redis setnx 实现互斥锁，
        //                   优点：编码简单、一致性强。 缺点：但会存在多个线程等待，牺牲了可见性，可能会导致死锁
        //          2. 逻辑过期：不设置过期时间，在 value 中手动添加过期时间字段，当数据过期时创建线程并让其进行缓存重建，其他线程返回旧数据。
        //                      优点：性能高，可见性强。缺点：浪费了内存空间，代码复杂

        // 缓存击穿解法一：互斥锁
        // return queryByIdWithMutex(id);
        // 缓存击穿解法二：逻辑过期
        return queryByIdWithLogicalExpire(id);
    }


    @Data
    @AllArgsConstructor
    private static class RedisDataWithExpireTime {
        private Object data;
        private LocalDateTime expireTime;
    }

    public static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newSingleThreadExecutor();

    private Shop queryByIdWithLogicalExpire(Long id) {
        String shopKey = RedisConstants.CACHE_SHOP_KEY + id;
        String shopStr = stringRedisTemplate.opsForValue().get(shopKey);
        if (StrUtil.isBlank(shopStr)) {
            throw new RuntimeException("数据未预热");
        }
        RedisDataWithExpireTime redisData = JSONUtil.toBean(shopStr, RedisDataWithExpireTime.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        // 判断是否过期
        if (redisData.getExpireTime().isAfter(LocalDateTime.now())) {
            // 还没过期
            return shop;
        }
        // 已经过期，尝试获取锁
        if (!tryLock(id)) {
            // 没有获取到锁，说明其他线程正在重建
            return shop;
        }
        // 创建线程并进行重建
        CACHE_REBUILD_EXECUTOR.submit(() -> {
            log.debug("jusr one thread can run to here");
            // 重建过程比较漫长
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                // ignore
            }
            this.saveShopToRedis(id, RedisConstants.CACHE_SHOP_TTL);
            this.unLock(id);
        });

        return shop;
    }

    private boolean tryLock(Long id) {
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(lockKey, RedisConstants.LOCK_VALUE, RedisConstants.LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unLock(Long id) {
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        stringRedisTemplate.delete(lockKey);
    }

    public boolean saveShopToRedis(Long id, Long expireSeconds) {
        Shop shop = this.getById(id);
        if (shop == null) {
            return false;
        }
        RedisDataWithExpireTime redisData = new RedisDataWithExpireTime(shop, LocalDateTime.now().plusSeconds(expireSeconds));
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
        return true;
    }

    private Shop queryByIdWithMutex(Long id) {
        try {
            // 先查看 Redis 中是否存在缓存
            String shopKey = RedisConstants.CACHE_SHOP_KEY + id;
            String shopStr = stringRedisTemplate.opsForValue().get(shopKey);
            // 判断需要重建缓存
            if (StrUtil.isNotBlank(shopStr)) {
                return JSONUtil.toBean(shopStr, Shop.class);
            }

            // 需要进行缓存重建，尝试获取锁
            while (!tryLock(id)) {
                Thread.sleep(50);

                // TODO 下面三行代码是否能够提高性能
                shopStr = stringRedisTemplate.opsForValue().get(shopKey);
                if (StrUtil.isNotBlank(shopStr)) {
                    return JSONUtil.toBean(shopStr, Shop.class);
                }
            }

            // 获取到锁，判断是否还需要进行重建
            shopStr = stringRedisTemplate.opsForValue().get(shopKey);
            if (StrUtil.isNotBlank(shopStr)) {
                return JSONUtil.toBean(shopStr, Shop.class);
            }

            // 模拟重建过程十分漫长
            Thread.sleep(500);
            // 确实需要重建
            log.debug("jusr one thread can run to here");
            Shop shop = this.getById(id);
            stringRedisTemplate.opsForValue().set(shopKey, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);

            return shop;
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            unLock(id);
        }
    }


    private Shop queryByIdWithPassThrough(Long id) {
        // 先查看 Redis 中是否存在缓存
        String shopKey = RedisConstants.CACHE_SHOP_KEY + id;
        String shopStr = stringRedisTemplate.opsForValue().get(shopKey);
        if (StrUtil.isNotBlank(shopStr)) {
            if (RedisConstants.NO_EXIST_VALUE.equals(shopStr)) {
                return null;
            }
            return JSONUtil.toBean(shopStr, Shop.class);
        }

        Shop shop = this.getById(id);

        if (shop == null) {
            // 缓存穿透：用户查询不存在的数据，请求一直到达数据库
            // 解决办法有下面几个：
            //      1. 将空数据保存到缓存中，编码简单但很占用内存空间
            //      2. 布隆过滤：布隆过滤器如果告诉你这个 key 不存在，他一定就不存在，由于使用位图存储所以比较省空间，但编码难度大
            //      3. 让 id 存在规侧且复杂性，能够再 Controller 进行鬼册过滤
            //      4. 必要的权限认证
            //      5  限流
            stringRedisTemplate.opsForValue().set(shopKey, RedisConstants.NO_EXIST_VALUE, RedisConstants.NO_EXIST_TTL, TimeUnit.MINUTES);
            return null;
        }
        // 缓存雪崩：大量 Key 同时失效，一般在一些批量导入功能发生
        // 解决方案： 1. 随机时间
        //          2. Redis 高可用：这是针对单个 Redis 节点宕机导致请求落到服务中
        //          3. 服务降级：当所有 Redis 都宕机时，采用服务降级可以避免数据库压过过大
        //          4. 多级缓存：浏览器缓存 -> Nginx 缓存 -> 本地服务缓存 -> Redis 缓存 -> 数据库
        stringRedisTemplate.opsForValue().set(shopKey, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);

        return shop;
    }

    @Override
    public boolean updateById(Shop shop) {

        // 使用缓存会带来数据不一致的问题，解决方案有三个：
        //      1. 内存淘汰：由 Redis 的内存淘汰机制让缓存失效，当用户查询时重建缓存可以达到数据库和缓存的一致性。不需要编码，效果差
        //      2. 过期时间：主动设置过期时间让缓存失效，可以保证一定的一致性，可用于兜底，适用于低一致性要求的场景
        //      3. 主动更新：可以保证较高的一致性，但是编码难度大，效果比较好
        // 这里选择【主动更新】，主动更新又有三种策略：
        //      1. Cache Aside：旁路更新，也就是更新数据库时更新/删除缓存，这种需要进行额外编码
        //      2. Read/Write thought：将缓存和数据库封装到一个服务中，由三方服务解决一致性问题，本地服务不需要关注。这种服务维护成本较高
        //      3. Write behind caching：写回，只对缓存进行读写，由缓存服务定时与数据库同步数据，保证最终一致性
        // 这里使用了 Cache aside 更新策略，Cache aside 实施时需要注意下面三个问题：
        //      1. 在操作缓存时是更新还是删除：删除缓存优于更新缓存
        //          1) 在写多读少的场景下如果采用更新缓存会导致很多无效更新，
        //          2) 如果采用更新缓存策略，缓存数据可能来之不易（需要重新组合、联表、远程调用）
        //          3）删除缓存可以对非热点输入进行淘汰，符合 lacy loading 的思想
        //          总的来说：删除缓存更轻量级、更简单、编码更容易
        //      2. 删除缓存和更新数据库如果有一个失败怎么办？
        //          1). 事务保证（前提是缓存、数据库支持事务）：在单机情况下可以使用 @Transactional 注解，如果是集群环境需要使用分布式事务 TCC
        //          2(. 补偿：重试（延迟删除）、或者任务队列等
        //          这里的缓存可以泛化为第三方服务，要保证数据一致性同样可以使用上面两种方法
        //      3. 删除缓存和更新数据库的顺序问题（并发情况下）
        //          1) 先更新数据库再更新缓存：在读线程完成查询缓存未命中后写线程完成更新数据库、删除缓存，读线程回写脏数据导致不一致
        //          2）先删除缓存再更新数据库：在写线程完成删除缓存后，读线程查询未命中并更新缓存，写线程再更新数据库导致数据不一致
        //          简而言之，写过程中伴随读过程的概率较高，以删除兜底比较稳妥
        //
        //
        if (this.getById(shop.getId()) == null) {
            return false;
        }
        // 更新数据库
        super.updateById(shop);
        // 删除缓存（Redis 是没有办法回滚的）
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + shop.getId());
        // TODO 解决 Redis 删除失败的场景

        return true;
    }
}
