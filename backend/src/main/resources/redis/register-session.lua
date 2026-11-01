-- KEYS[1]：会话集合；ARGV[1]：Session ID；ARGV[2]：Java 校验的毫秒 TTL。
-- SADD 返回 0 表示成员已存在，仍需刷新 TTL；成功标记取自 PEXPIRE。
redis.call('SADD', KEYS[1], ARGV[1])
return redis.call('PEXPIRE', KEYS[1], ARGV[2])
