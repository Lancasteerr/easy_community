-- KEYS[1]：账号计数；KEYS[2]：IP 计数；ARGV[1]：固定的 16 分钟毫秒 TTL。
-- 只负责记录失败，不占用认证额度；每次失败均刷新两个维度的 TTL。
-- 脚本异常直接交给调用方；已执行写入不保证回滚，不自动重试。
redis.call('INCR', KEYS[1])
redis.call('PEXPIRE', KEYS[1], ARGV[1])
redis.call('INCR', KEYS[2])
redis.call('PEXPIRE', KEYS[2], ARGV[1])
return 1
