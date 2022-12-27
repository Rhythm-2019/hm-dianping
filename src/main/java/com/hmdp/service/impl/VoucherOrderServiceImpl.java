package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    @Value("${spring.application.name}")
    private String appName;

    private static final BlockingQueue<VoucherOrder> createOrderQueue = new ArrayBlockingQueue<>(1024 * 1024);
    private static final ExecutorService CREATE_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    private static DefaultRedisScript<Long> seckillScript;

    static {
        seckillScript = new DefaultRedisScript<>();
        seckillScript.setLocation(new ClassPathResource("redis/seckill.lua"));
        seckillScript.setResultType(Long.class);
    }


    @Override
    public Result secKillVoucher(Long voucherId, Long userId) {
        // 单机版本的秒杀，存在超卖超卖问题
        // return this.secKillVoucherSingleThread(voucherId, userId);
        // 使用乐观锁解决超卖问题
        // return this.secKillVoucherOptimisticLock(voucherId, userId);  // 这样调用 this 指向原对象而非代理对象，没有事务增强
        // return ((IVoucherOrderService) AopContext.currentProxy()).secKillVoucherOptimisticLock(voucherId, userId);

        // 使用悲观锁完成秒杀，单机情况下
        // synchronized (userId.toString().intern()) {
        //    // 这个同步锁要加载外面，包含事务控制的代码
        //    return ((IVoucherOrderService) AopContext.currentProxy()).secKillVoucherUserOnce(voucherId, userId);
        //}

        // 使用悲观锁完成秒杀，手写分布式锁
        // RedisLock lock = new RedisLock("voucher", stringRedisTemplate);
        // try {
        //     if (!lock.tryLock(12000, TimeUnit.SECONDS)) {
        //         return Result.fail("一人只能下一单");
        //     }
        //     return ((IVoucherOrderService) AopContext.currentProxy()).secKillVoucherUserOnce(voucherId, userId);
        // } finally {
        //     lock.unlock();
        // }

        // 使用悲观锁完成秒杀，使用工具类
        /*
        RLock lock = redissonClient.getLock("voucher:seckill:" + userId);
        if (!lock.tryLock()) {
            return Result.fail("一人只能下一单");
        }
        try {
            return ((IVoucherOrderService) AopContext.currentProxy()).secKillVoucherUserOnce(voucherId, userId);
        } finally {
            // 注意解锁时如果不是自己加的锁则会报错
            lock.unlock();
        }
        */

        // 使用异步方式优化，将购买权限资格判断放入 Redis，创建订单的任务交给其他线程异步执行
        return secKillVoucherByRedis(voucherId, userId);
    }


    private Result secKillVoucherByRedis(Long voucherId, Long userId) {
        long orderId = redisIdWorker.nextId(RedisConstants.VOUCHER_ORDER_ID_KEY);
        Long result = stringRedisTemplate.execute(seckillScript,
                Arrays.asList(RedisConstants.SECKILL_STOCK_KEY + voucherId, RedisConstants.SECKILL_ORDER_USER_KEY + voucherId, RedisConstants.SECKILL_ORDER_STREAM_NAME),
                voucherId.toString(), userId.toString(), orderId + "");
        switch (result.intValue()) {
            case 0:
                VoucherOrder voucherOrder = new VoucherOrder();
                voucherOrder.setId(orderId);
                voucherOrder.setUserId(userId);
                voucherOrder.setVoucherId(voucherId);
                // 依赖本地消息队列存在下面问题：
                // 1. 服务不稳定，宕机后数据会丢失
                // 2. 任务积压会导致 OOM
                // createOrderQueue.add(voucherOrder);

                return Result.ok(orderId);
            case 1:
                return Result.fail("该商铺不参与秒杀");
            case 2:
                return Result.fail("库存不足");
            case 3:
                return Result.fail("一人限购一单");
            default:
                throw new RuntimeException("No expected");
        }
    }

    @PostConstruct
    public void init() {
        CREATE_ORDER_EXECUTOR.submit(new CreateOrderTask(appName, appName + RandomUtil.randomString(6)));
    }

    private class CreateOrderTask implements Runnable {

        private String groupId;
        private String consumerId;

        public CreateOrderTask(String groupId, String consumerId) {
            this.groupId = groupId;
            this.consumerId = consumerId;
        }

        @Override
        public void run() {
            this.handle(false);
        }

        private void handle(boolean meetError) {
            while (true) {
                // VoucherOrder voucherOrder = createOrderQueue.take();

                try {
                    // 使用 Stream 获取消息
                    ReadOffset readOffset = ReadOffset.lastConsumed();
                    if (meetError) {
                        readOffset = ReadOffset.from("0");
                    }
                    List<MapRecord<String, Object, Object>> msgRecordList = stringRedisTemplate.opsForStream().read(
                            Consumer.from(this.groupId, this.consumerId),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(RedisConstants.SECKILL_ORDER_STREAM_NAME, readOffset));
                    if (msgRecordList == null || msgRecordList.isEmpty()) {
                        if (meetError) {
                            break;
                        }
                        continue;
                    }
                    MapRecord<String, Object, Object> mapRecord = msgRecordList.get(0);
                    Map<Object, Object> msgMap = mapRecord.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(msgMap, new VoucherOrder(), true);

                    this.createOrder(voucherOrder);

                    stringRedisTemplate.opsForStream().acknowledge(this.groupId, this.consumerId, mapRecord.getId());
                } catch (Exception e) {
                    log.error("消费失败", e);
                    if (!meetError) {
                        handle(true);
                    }
                }
            }
        }


        private void createOrder(VoucherOrder voucherOrder) {
            SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherOrder.getVoucherId());
            if (seckillVoucher == null) {
                log.error(String.format("下单失败：找不到优惠券：%s", voucherOrder));
                return;
            }
            LocalDateTime now = LocalDateTime.now();
            if (now.isBefore(seckillVoucher.getBeginTime())) {
                log.error(String.format("下单失败：秒杀未开始：%s", voucherOrder));
                return;
            }
            if (now.isAfter(seckillVoucher.getEndTime())) {
                log.error(String.format("下单失败：秒杀已结束：%s", voucherOrder));
                return;
            }
            if (seckillVoucher.getStock() < 1) {
                log.error(String.format("下单失败：库存不足：%s", voucherOrder));
                return;
            }
            // 扣减库存【存在超卖问题】
            boolean isSuccess = seckillVoucherService.update()
                    .setSql("stock = stock - 1")
                    .gt("stock", 0)
                    .eq("voucher_id", voucherOrder.getVoucherId())
                    .update();
            VoucherOrderServiceImpl.this.save(voucherOrder);
        }
    }

    @PreDestroy
    public void destroy() {
        CREATE_ORDER_EXECUTOR.shutdown();
    }

    @Override
    @Transactional
    public Result secKillVoucherOptimisticLock(Long voucherId, Long userId) {
        // 检查优惠券是否足够
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
        if (seckillVoucher == null) {
            return Result.fail("找不到优惠券");
        }
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(seckillVoucher.getBeginTime())) {
            return Result.fail("秒杀未开始");
        }
        if (now.isAfter(seckillVoucher.getEndTime())) {
            return Result.fail("秒杀已结束");
        }

        if (seckillVoucher.getStock() < 1) {
            return Result.fail("库存不足");
        }
        // 保存库存，使用乐观锁：
        // 1.在更新时检查库存是不是和之前获取的一样： eq("stock", seckillVoucher.getStock())
        // 2 对于非数字、非单调的数据，可以添加 version 字段
        // 3 第一种方式用户抢购失败率比较高，我们可以取巧使用 .gt("stock", 0) 提高效率
        boolean isSuccess = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                //      .gt("stock", 0)
                .eq("stock", seckillVoucher.getStock())
                .eq("voucher_id", voucherId)
                .update();
        if (!isSuccess) {
            return Result.fail("库存扣除失败");
        }

        // 创建D订单
        VoucherOrder voucherOrder = new VoucherOrder();
        long id = redisIdWorker.nextId(RedisConstants.VOUCHER_ORDER_ID_KEY);
        voucherOrder.setId(id);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);

        this.save(voucherOrder);
        return Result.ok(id);
    }

    @Override
    @Transactional
    public Result secKillVoucherUserOnce(Long voucherId, Long userId) {
        // 检查优惠券是否足够
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
        if (seckillVoucher == null) {
            return Result.fail("找不到优惠券");
        }
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(seckillVoucher.getBeginTime())) {
            return Result.fail("秒杀未开始");
        }
        if (now.isAfter(seckillVoucher.getEndTime())) {
            return Result.fail("秒杀已结束");
        }
        if (seckillVoucher.getStock() < 1) {
            return Result.fail("库存不足");
        }
        VoucherOrder voucherOrder = this.getOne(new LambdaQueryWrapper<VoucherOrder>().eq(VoucherOrder::getUserId, userId));
        if (voucherOrder != null) {
            return Result.fail("一人只能下一单");
        }
        // 这里依然要使用乐观锁
        boolean isSuccess = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .gt("stock", 0)
                .eq("voucher_id", voucherId)
                .update();
        if (!isSuccess) {
            return Result.fail("库存扣除失败");
        }

        // 创建D订单
        voucherOrder = new VoucherOrder();
        long id = redisIdWorker.nextId(RedisConstants.VOUCHER_ORDER_ID_KEY);
        voucherOrder.setId(id);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);

        this.save(voucherOrder);
        return Result.ok(id);
    }

    @Transactional
    public Result secKillVoucherSingleThread(Long voucherId, Long userId) {
        // 检查优惠券是否足够
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
        if (seckillVoucher == null) {
            return Result.fail("找不到优惠券");
        }
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(seckillVoucher.getBeginTime())) {
            return Result.fail("秒杀未开始");
        }
        if (now.isAfter(seckillVoucher.getEndTime())) {
            return Result.fail("秒杀已结束");
        }
        // 查看库存是否足够，在多线程下这一步会存在问题
        // 悲观锁：认为一定会存在资源竞争，保证只有一个线程对数据进行修改
        //          1. 本地锁      2. 分布式锁
        // 乐观锁：认为不会有很多线程竞争，在数据被修改时进行检查
        //          1. 添加版本字段    2. 以数据作为版本
        if (seckillVoucher.getStock() < 1) {
            return Result.fail("库存不足");
        }
        // 减少库存【存在超买问题】
        // seckillVoucher.setStock(seckillVoucher.getStock() - 1);
        // 保存库存
        // seckillVoucherService.updateById(seckillVoucher);

        // 扣减库存【存在超卖问题】
        seckillVoucherService.update().setSql("stock = stock - 1").eq("voucher_id", voucherId).update();

        // 创建D订单
        VoucherOrder voucherOrder = new VoucherOrder();
        long id = redisIdWorker.nextId(RedisConstants.VOUCHER_ORDER_ID_KEY);
        voucherOrder.setId(id);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);

        this.save(voucherOrder);
        return Result.ok(id);
    }

}
