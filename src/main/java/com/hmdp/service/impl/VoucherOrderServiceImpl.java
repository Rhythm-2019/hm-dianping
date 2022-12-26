package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
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
    private Result secKillVoucherSingleThread(Long voucherId, Long userId) {
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
