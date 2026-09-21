-- 原子释放分布式锁：校验value匹配后才删除，防止误删其他线程持有的锁
if redis.call('get', KEYS[1]) == ARGV[1] then
    return redis.call('del', KEYS[1])
else
    return 0
end
