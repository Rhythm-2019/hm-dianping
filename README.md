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

秒杀优惠券一人只能下一单，所以我们需要添加额外校验
