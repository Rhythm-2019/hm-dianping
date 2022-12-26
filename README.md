#  黑马点评



## 架构图



## Feature

###  用户登录注册

Session 是服务器为用户开辟的独立空间，通过 Cookie 中的标识定位，优点在于使用简单快捷、用户隔离，但存在下面问题：
1. 本地服务由于一些原因（代码质量、并发能力等）问题，稳定性较低，容易宕机。就拿发送手机验证码为例，当用户点击发送验证码后服务进行了重启，保存在 session 中的验证码丢失
2. 本地服务携带状态，扩容比较困难

为了解决上面问题，我们可以寻找一个高性能、稳定的三方服务对用户进行托管，Redis 是我们的首选。该项目中使用了 String 类型存储手机验证码，Hash 类型存储对象信息。

> 在使用 Redis 时需要根据实际需求确定存储的数据结构





### 为商铺查询添加缓存功能

为了提升查询效率，我们将商铺信息序列化并保存到 Redis 中，当用户需要查询商铺时先查询缓存，若未命中则手动将数据放入 Redis 中（这一步称为**缓存重建**）

#### 缓存更新策略

虽然这样做能提高性能，但编码时会遇到一个问题：由于 Redis 保存了数据的副本，当数据库数据发生变更时会导致数据库和缓存数据不一致的问题。解决方案如下：

|          | 内存淘汰                                                     | 过期时间                                           | 主动更新                               |
| -------- | ------------------------------------------------------------ | -------------------------------------------------- | -------------------------------------- |
| 说明     | 当 Redis 内存不足时会触发内存淘汰机制，当用户查询时触发缓存的更新。这张航发 | 主动为数据设置过期时间，过期后用户查询触发缓存更新 | 在更新数据库数据时一并更新缓存中的数据 |
| 一致性   | 差                                                           | 一般                                               | 较好                                   |
| 维护成本 | 无                                                           | 低                                                 | 高                                     |

我们可以根据一致性的要求进行选择。项目中选择使用主动更新的方式保证数据的一致性。具体的，主动更新的方案又有三种

|      | Cache Aside Pattern                                | Read/Write Thought Pattern                             | Write Bebind Caching Pattern                                 |
| ---- | -------------------------------------------------- | ------------------------------------------------------ | ------------------------------------------------------------ |
| 说明 | 旁路更新，由我们手动编写代码完成数据库和缓存的更新 | 数据库和缓存整合到同一个服务中，我们无需关心一致性问题 | 写回，调用者只需要操作缓存即可，由额外的线程或缓存服务本身进行数据同步 |
| 优势 | 可控、不需要其他组件                               | 对原有代码没有侵入                                     | 性能较高                                                     |
| 劣势 | 需要额外编码，不能保证强一致性                     | 服务成本高                                             | 编码复杂，需要考虑线程数据丢失问题                           |

项目中选择 Cache Aside Pattern。选择该模式时需要考虑下面几个问题：

   1. 在操作缓存时是更新还是删除：答案时删除缓存优于更新缓存

      * 在写多读少的场景下如果采用更新缓存会导致很多无效更新
      * 如果采用更新缓存策略，缓存数据可能来之不易（需要重新组合、联表、远程调用）
      * 删除缓存可以对非热点输入进行淘汰，符合 lacy loading
      * 更新缓存更容易遇到数据不一致的问题

   2. 删除缓存和更新数据库如果有一个失败怎么办？

      * 事务：如果缓存服务和数据库都支持事务的情况下可以使用 @Transactional 注解，集群环境使用分布式事务
      * 补偿：重试（延迟删除）、或者任务队列等

      > 这里的缓存可以泛化为第三方服务，要保证数据一致性同样可以使用上面两种方法

   3. 删除缓存和更新数据库的顺序问题（并发情况下）

      * 先更新数据库再更新缓存：在读线程完成查询缓存未命中后写线程完成更新数据库、删除缓存，读线程回写脏数据导致不一致
      * 先删除缓存再更新数据库：在写线程完成删除缓存后，读线程查询未命中并更新缓存，写线程再更新数据库导致数据不一致

      ![先删除缓存还是先更新数据库](D:\Project\hmdp\hm-dianping\assets\image-20221223121121053.png)

简而言之，写过程中伴随读过程的概率较高，以删除兜底比较稳妥。



#### 缓存穿透

当用户请求不存在的数据时，请求永远会到达数据库，恶意用户可以利用这一点对数据库压力。解决方案如下：

1. 为不存在的数据创建空的缓存。编码简单，但浪费内存、且会造成数据不一致（如果数据真的被产生，缓存中却是空数据）
2. 布隆过滤器：节约内存，但编码复杂，如果一个 key 经过布隆过滤器发现数据不存在，那么数据是真的不存在缓存。用法是当用户需要保存数据到数据库时向过滤器插入该数据，用户查询时使用不布隆过滤器判断数据是否存在于数据库

上面两种方式都比较被动，我们还可以通过下面方式主动减少穿透的可能

1. 为 id 添加规则和复杂度，在外部进行规则校验
2. 权限校验
3. 限流

项目中使用的是为不存在的数据添加空的缓存。

#### 缓存雪崩

大量的 key 失效或是缓存服务宕机，到这请求全部到达数据库，给数据库带来压力

1. 为过期时间添加随机数，避免多个 key 同时过期
2. Redis 高可用部署
3. 服务降级
4. 多级缓存

在该项目中没有体现

#### 缓存击穿

在高并发场景下，某一个热点的 key 突然失效，由于缓存重建时间过长，导致多个线程同时执行缓存重建，即影响性能又对数据库造成压力。解决方案如下：

|      | 互斥锁                                             | 逻辑过期                                                     |
| ---- | -------------------------------------------------- | ------------------------------------------------------------ |
| 说明 | 使用互斥锁让单个线程进行缓存重建，其他线程 pending | 为数据添加一个过期时间字段，手动校验是否过期，如果过期了获取锁、创建线程进行重建，原线程和其他线程返回旧值 |
| 优势 | 保证一致性，不浪费内存                             | 保证可见性，性能较高                                         |
| 劣势 | 牺牲可见性，可能会导致死锁                         | 牺牲一致性，额外内存消耗                                     |

项目对这两种方式都进行了试验，具体选择哪种方案需要自己权衡





##  优惠券秒杀

### 全局唯一ID生成

用户可以选择购买优惠券，购买后会生成订单，数据库中的其他表的编号使用数据库自增，但订单比较特殊，订单编号需要返回给用户，自增编号容易暴露信息（比如两次下单之间该平台产生了多少订单），在后期订单数量较大的情况下需要将数据分配到不同的表或库，使用数据库自增编号难以保证编号的唯一性，因此我们需要手动生成，

> 分布式 ID 要遵循 高性能、高可用、自增、唯一和安全

* UUID：使用时间错和 MAC 地址生成，字符串，不符合自增性
* 雪花算法：一个Snowflake ID有64位元。前41位是时间戳，表示了自选定的时期（英语：Epoch_(computing)）以来的毫秒数。 接下来的10位代表计算机ID，防止冲突。 其余12位代表每台机器上生成ID的序列号，这允许在同一毫秒内创建多个Snowflake ID。最后以十进制将数字序列化。速度快但强依赖于本地时间
* Redis 自增ID：使用 Redis 的 incr 命令生成自增序列，再拼接业务字段。这种方式编码简单，但需要额外考虑 Redis 的稳定性，如果 Redis 宕机后，AOF 持久化恢复缓慢，RDB 恢复会丢失数据
* 写段模式：用于批量 ID 申领，具体可以参考：[一口气说出9种分布式ID生成方式，面试官有点懵了](https://zhuanlan.zhihu.com/p/107939861)

在数据量较大的情况下，订单数据可能需要被分配到不同的表货数据库，因此订单编号的生成

### 优惠券下单

项目中优惠券下单的业务流程比较简单，只需要进行库存扣减和创建订单即可。但扣减库存的时候需要经过查询库存-判断库存是否充足-库存更新，由于库存信息是共享资源，所以需要注意线程安全问题，面对资源（具体为数据）竞争问题我们会有下面两种态度：

1. 悲观锁：资源一定会被竞争，我们可以使用互斥锁对资源进行保护
2. 乐观锁：数据可能不会出现竞争或者竞争不激烈，我们可以不加进行保护，先使用，更新时校验数据是否被别人更新，如果没有直接覆盖，如果被别人更新了就放弃或重试。

优惠券下单中的库存扣减使用了乐观锁

```java
boolean isSuccess = seckillVoucherService.update()
    .setSql("stock = stock - 1")
    .gt("stock", 0)
    .eq("voucher_id", voucherId)
    .update();
```

另外，在 Spring 中非事务方法调用当前类的事务方法时需要先获取代理对象再调用

```java
return ((IVoucherOrderService) AopContext.currentProxy()).secKillVoucherOptimisticLock(voucherId, userId);
```



### 一人一单

秒杀优惠券一人只能下一单，所以下单前需要校验用户是否已下单并创建订单，这里又存在着线程安全问题，但这次我们没有办法使用乐观锁解决，只能使用悲观锁，单节点情况下我们可以对用户编号进行加锁，注意要将 id 转换为字符串并放入常量池中，这样同一个用户才能获取到同一把锁。



在集群环境下我们可以使用分布式锁，分布式锁是指多进程可见的互斥锁，分布式锁需要满足下面几个条件：

1. 多进程可见
2. 互斥
3. 高可用
4. 高性能
5. 安全性
6. 其他增强功能（比如可重入、重试、自动续约等）

我们可以依赖中间件实现分布式锁

|        | MySQL                                      | Redis                  | Zookeeper                            |
| ------ | ------------------------------------------ | ---------------------- | ------------------------------------ |
| 互斥   | 利用索引或者排他锁实现                     | 使用 setnx 实现        | 创建临时顺序节点                     |
| 高可用 | 主从模式，有保障                           | 主从、哨兵模式，有保障 | ZAB，有保障                          |
| 高性能 | 一般                                       | 好                     | 一般                                 |
| 安全性 | 可以依靠事务，当连接断开主动释放，可以保障 | 利用超时时间，可以保障 | 连接断开后自动删除临时节点，可以保障 |

这三种方式具体如何实现分布式锁可以参考：[分布式锁看这篇就够了](https://zhuanlan.zhihu.com/p/42056183)，这里主要讨论 Redis

### 自己实现Redis分布式锁

* 加锁：setnx 锁定并设置过期时间，一步到位，当 Redis 执行 setnx key value 时，只有当 key 不存在时才能设置成功。
* 释放锁：将 key 删除即可

注意避免线程A获取锁后由于业务执行时间大于有效时间锁自动释放，B线程获取锁，A线程又释放了B线程的锁的情况。因此加锁时需要添加主机 + 线程标识，删除时校验。删除时校验需要保证原子性，所以还需要用到 lua 脚本

```lua
if (redis.call("GET",  KEYS[1]) == ARGV[1]) then
    return redis.call("DEL", KEYS[1])
end
return 0
```

加锁解锁代码如下

```java
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
```

### Redison 如何实现分布式锁

在生产环境中我们可以使用 redison 提供的分布式锁实现，我们可以从上面几个角度分析一下它的工作原理

**互斥**



根据源码，我们可以看到加锁的 lua 脚本如下

```lua
--为了实现可重入锁的功能，需要使用 hash 结构保存线程标识和当前线程获取锁的次数
if (redis.call('exists', KEYS[1]) == 0) then
    -- 锁不存在，直接加锁
    redis.call('hincrby', KEYS[1], ARGV[2], 1);
    redis.call('pexpire', KEYS[1], ARGV[1]); 
    return nil;
end;
-- 锁存在，查看当前持有者是否是自己，如果是则计数加一，实现重入
if (redis.call('hexists', KEYS[1], ARGV[2]) == 1) then
    redis.call('hincrby', KEYS[1], ARGV[2], 1);
    redis.call('pexpire', KEYS[1], ARGV[1]); 
    return nil; 
end;
-- 返回剩余有效时间
return redis.call('pttl', KEYS[1]);
```

下面是解锁的 lua 脚本

```lua
--判断当前锁持有者是不是自己
if (redis.call('hexists', KEYS[1], ARGV[3]) == 0) then
    return nil;
end; 

local counter = redis.call('hincrby', KEYS[1], ARGV[3], -1); 
if (counter > 0) then
    -- 重置有效期
    redis.call('pexpire', KEYS[1], ARGV[2]); 
    return 0; 
else
    -- 已经是最外层的解锁动作，删除锁
    redis.call('del', KEYS[1]); 
    -- 发布消息，其他希望获取锁的线程会被唤醒
    redis.call('publish', KEYS[2], ARGV[1]); return 1; 
end;
return nil;
```

通过上面两个脚本，redisson 实现了互斥和可重入的功能

**安全性**

创建锁时我们可以设置等待时间 leaseTime，如果没有设置则会触发看门狗处理逻辑

```java
private RFuture<Boolean> tryAcquireOnceAsync(long waitTime, long leaseTime, TimeUnit unit, long threadId) {
    if (leaseTime != -1L) {
        return this.tryLockInnerAsync(waitTime, leaseTime, unit, threadId, RedisCommands.EVAL_NULL_BOOLEAN);
    } else {
        RFuture<Boolean> ttlRemainingFuture = this.tryLockInnerAsync(waitTime, this.commandExecutor.getConnectionManager().getCfg().getLockWatchdogTimeout(), TimeUnit.MILLISECONDS, threadId, RedisCommands.EVAL_NULL_BOOLEAN);
        ttlRemainingFuture.onComplete((ttlRemaining, e) -> {
            if (e == null) {
                if (ttlRemaining) {
                    // 看门狗，定时刷新有效时间
                    this.scheduleExpirationRenewal(threadId);
                }

            }
        });
        return ttlRemainingFuture;
    }
}
```

进入 scheduleExpirationRenewal 方法，这里创建了一个定时任务用于刷新有效时间

```java
private void renewExpiration() {
        ExpirationEntry ee = (ExpirationEntry)EXPIRATION_RENEWAL_MAP.get(this.getEntryName());
        if (ee != null) {
            Timeout task = this.commandExecutor.getConnectionManager().newTimeout(new TimerTask() {
                public void run(Timeout timeout) throws Exception {
                    ExpirationEntry ent = (ExpirationEntry)RedissonLock.EXPIRATION_RENEWAL_MAP.get(RedissonLock.this.getEntryName());
                    if (ent != null) {
                        Long threadId = ent.getFirstThreadId();
                        if (threadId != null) {
                            RFuture<Boolean> future = RedissonLock.this.renewExpirationAsync(threadId);
                            future.onComplete((res, e) -> {
                                if (e != null) {
                                    RedissonLock.log.error("Can't update lock " + RedissonLock.this.getName() + " expiration", e);
                                } else {
                                    if (res) {
                                        RedissonLock.this.renewExpiration();
                                    }

                                }
                            });
                        }
                    }
                }
                // 三分之一 30 秒，也就是 10 秒更新一次
            }, this.internalLockLeaseTime / 3L, TimeUnit.MILLISECONDS);
            ee.setTimeout(task);
        }
    }
```



最后会执行一段更新过期时间的 lua 脚本

```lua
if (redis.call('hexists', KEYS[1], ARGV[2]) == 1) then
    redis.call('pexpire', KEYS[1], ARGV[1]); 
    return 1;
end;
return 0;
```



**可重试**

当我们创建一个分布式锁时可以传递 waitTime，这里没有让 CPU 进行忙时等待，而是利用了 Redis 的发布订阅和信号量功能

```java
 public boolean tryLock(long waitTime, long leaseTime, TimeUnit unit) throws InterruptedException {
        long time = unit.toMillis(waitTime);
        long current = System.currentTimeMillis();
        long threadId = Thread.currentThread().getId();
        // 尝试获取锁，ttl为null表示成功，ttl为数字表示锁的有效期
        Long ttl = tryAcquire(waitTime, leaseTime, unit, threadId);
        // lock acquired
        if (ttl == null) {
            return true;
        }
        // time 表示剩余的 waitTime
        time -= System.currentTimeMillis() - current;
        if (time <= 0) {
            // 已经没有时间了，返回失败
            acquireFailed(waitTime, unit, threadId);
            return false;
        }
        
        current = System.currentTimeMillis();
        // 订阅该锁，当锁被释放的时候会执行 publish
        RFuture<RedissonLockEntry> subscribeFuture = subscribe(threadId);
        if (!subscribeFuture.await(time, TimeUnit.MILLISECONDS)) {
            // 等待超时，取消订阅
            if (!subscribeFuture.cancel(false)) {
                subscribeFuture.onComplete((res, e) -> {
                    if (e == null) {
                        unsubscribe(subscribeFuture, threadId);
                    }
                });
            }
            acquireFailed(waitTime, unit, threadId);
            return false;
        }

        try {
            // 再次计算剩余时间
            time -= System.currentTimeMillis() - current;
            if (time <= 0) {
                acquireFailed(waitTime, unit, threadId);
                return false;
            }
        
            while (true) {
                long currentTime = System.currentTimeMillis();
                // 再次尝试获取锁
                ttl = tryAcquire(waitTime, leaseTime, unit, threadId);
                // lock acquired
                if (ttl == null) {
                    return true;
                }
				// 再次计算剩余时间
                time -= System.currentTimeMillis() - currentTime;
                if (time <= 0) {
                    acquireFailed(waitTime, unit, threadId);
                    return false;
                }

                // waiting for message
                currentTime = System.currentTimeMillis();
                if (ttl >= 0 && ttl < time) {
                    // 如果锁的有效期比剩余的 waitTime 还要短，时间还比较充裕，我们可以再等待 ttl 时间
                    subscribeFuture.getNow().getLatch().tryAcquire(ttl, TimeUnit.MILLISECONDS);
                } else {
                    // 时间不充裕，只能等待 waitTime 时间
                    subscribeFuture.getNow().getLatch().tryAcquire(time, TimeUnit.MILLISECONDS);
                }

                time -= System.currentTimeMillis() - currentTime;
                if (time <= 0) {
                    acquireFailed(waitTime, unit, threadId);
                    return false;
                }
            }
        } finally {
            unsubscribe(subscribeFuture, threadId);
        }
//        return get(tryLockAsync(waitTime, leaseTime, unit));
    }
```

利用了 Redis 的发布订阅功能让线程挂起，减少 CPU 的性能损耗

**主从一致性问题**

如果我们的 Redis 使用主从模式部署，当我们去主节点获取锁成功后主节点宕机，数据未同步，从节点升级为主节点，其他线程又能获取到锁。

这个问题不太好解决，Redisson 提供了连锁和红锁，但又被业界人士质疑：

无论怎么说分布式锁都没有办法保证完美的线程安全，因此我们还是要在业务上做一些兜底的措施。

