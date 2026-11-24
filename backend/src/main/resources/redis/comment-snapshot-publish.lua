-- 原子检查租约、当前指针与构建完整性，再公布楼层的完整索引。
if redis.call('GET', KEYS[1]) ~= ARGV[1] then return nil end
local current = redis.call('GET', KEYS[2])
if current then
    if current ~= ARGV[2] then return nil end
elseif ARGV[5] == '1' then
    -- 硬过期由请求回源，迟到的后台刷新不能复活旧版本。
    return nil
end
if redis.call('ZCARD', KEYS[4]) ~= tonumber(ARGV[4]) then return nil end

-- 保留元数据中的占位字段，让升级前的回复快照仍能被读取。
local payload = table.concat({ARGV[3], '0', ARGV[6], ARGV[7], ARGV[4]}, '|')
if ARGV[4] ~= '0' then redis.call('PEXPIRE', KEYS[4], ARGV[8]) end
redis.call('PSETEX', KEYS[3], ARGV[8], payload)
redis.call('PSETEX', KEYS[2], ARGV[8], payload)
if current then
    for i = 5, 6 do
        if redis.call('PTTL', KEYS[i]) > 10000 then redis.call('PEXPIRE', KEYS[i], 10000) end
    end
end
return payload
