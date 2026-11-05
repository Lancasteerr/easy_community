-- 计数只从SQL填充，现有缓存不续期；不执行乐观INCR或写回数据库。
for i,key in ipairs(KEYS) do
    local kind = redis.call('TYPE', key).ok
    if kind ~= 'none' and kind ~= 'string' then return redis.error_reply('LIKE_COUNT invalid type') end
    if not string.match(ARGV[i+1], '^%d+$') then return redis.error_reply('LIKE_COUNT invalid count') end
end
for i,key in ipairs(KEYS) do redis.call('SET', key, ARGV[i+1], 'NX', 'EX', ARGV[1]) end
return 1
