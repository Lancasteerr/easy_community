-- KEYS[1]：计数 Key；ARGV[1]：由 Java 校验的正整数毫秒 TTL。
-- 每次递增均刷新 TTL；通过字符串返回计数，避免 Lua 数值精度损失。
redis.call('INCR', KEYS[1])
redis.call('PEXPIRE', KEYS[1], ARGV[1])
return redis.call('GET', KEYS[1])
