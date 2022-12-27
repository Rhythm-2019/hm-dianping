local stockKey = KEYS[1]
local orderUserKey = KEYS[2]
local streamKey = KEYS[3]

local voucherId = ARGV[1]
local userId = ARGV[2]
local orderId = ARGV[3]

-- 判断库存是否重组
if (redis.call("EXISTS", stockKey) == 0) then
    return 1
end
if (tonumber(redis.call("GET", stockKey)) == 0) then
    -- 库存不足
    return 2
end
if (redis.call("SISMEMBER", orderUserKey, userId) == 1) then
    -- 已经购买过该优惠券
    return 3
end

-- 扣减库存
redis.call("INCRBY", stockKey, "-1")
-- 添加用户
redis.call("SADD", orderUserKey, userId)
-- 发布创建订单的消息
redis.call("XADD", streamKey, "*", "id", orderId, "voucherId", voucherId, "userId", userId)
return 0