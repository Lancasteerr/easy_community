-- 原子检查租约、当前指针与构建完整性，再公布完整的索引。
if redis.call('GET', KEYS[1]) ~= ARGV[1] then return nil end
local current = redis.call('GET', KEYS[2])
if current then
    if current ~= ARGV[2] then return nil end
elseif ARGV[6] == '1' then
    -- 已物理过期由 Web 回源，迟到的后台消息不得复活旧版本。
    return nil
end
if redis.call('ZCARD', KEYS[4]) ~= tonumber(ARGV[4]) then return nil end

local rank = '0'
if ARGV[5] == '1' then
    redis.call('INCR', KEYS[5])
    rank = redis.call('GET', KEYS[5])
end
local payload = table.concat({ARGV[3], rank, ARGV[7], ARGV[8], ARGV[4]}, '|')
if ARGV[4] ~= '0' then redis.call('PEXPIRE', KEYS[4], ARGV[9]) end
redis.call('PSETEX', KEYS[3], ARGV[9], payload)
redis.call('PSETEX', KEYS[2], ARGV[9], payload)
-- 时间游标可以跨快照，保留旧索引片刻供已经开始组装的请求读取。
if ARGV[5] == '0' and current then
    for i = 6, 7 do
        if redis.call('PTTL', KEYS[i]) > 10000 then redis.call('PEXPIRE', KEYS[i], 10000) end
    end
end
return payload
