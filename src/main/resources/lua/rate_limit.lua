-- 原子限流：将GET + 计数判断 + INCR + 设过期合并为单条原子命令
-- KEYS[1]: redisKey  ARGV[1]: maxCount  ARGV[2]: ttlSeconds
-- 返回值: -1 表示触发限流，>=0 表示当前计数(放行)
local key = KEYS[1]
local maxCount = tonumber(ARGV[1])
local ttl = tonumber(ARGV[2])

local count = redis.call('GET', key)
if count == false then
    redis.call('SET', key, 1, 'EX', ttl)
    return 1
end
count = tonumber(count)
if count >= maxCount then
    return -1
end
return redis.call('INCR', key)

--固定窗口优点是实现简单，Redis读写性能高
--固定窗口限流的缺陷
--窗口临界点突刺问题：
--假设窗口 10s，maxCount=1。
--在第 9.9s 访问一次，计数器 = 1；
--0.2s 后 key 过期，窗口重置；
--紧接着新窗口立刻又可以访问一次。
--极短时间内连续两次请求被放行，出现流量突刺。
--短链接项目并发不高，这个问题通常可以接受。
