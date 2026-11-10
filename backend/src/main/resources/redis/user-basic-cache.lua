-- 两个同槽Key：用户操作锁、展示数据。先检查参数与类型，再验证token并修改。
local token, action, payload = ARGV[1], ARGV[2], ARGV[3]
local ttl = tonumber(ARGV[4])
local allowed = {PUBLISH=true, MISSING=true, INVALIDATE=true, UNLOCK=true}
if #KEYS ~= 2 or not token or #token == 0 or not allowed[action]
    or not ttl or ttl < 1 or ttl > 300000 or ttl ~= math.floor(ttl) then
    return redis.error_reply('invalid user cache arguments')
end
for i = 1, 2 do
    local kind = redis.call('TYPE', KEYS[i]).ok
    if kind ~= 'none' and kind ~= 'string' then return redis.error_reply('invalid user cache key type') end
end
if redis.call('GET', KEYS[1]) ~= token then return 0 end
if action == 'UNLOCK' then
    redis.call('DEL', KEYS[1])
elseif action == 'INVALIDATE' then
    redis.call('DEL', KEYS[2])
elseif action == 'MISSING' then
    redis.call('PSETEX', KEYS[2], ttl, 'M')
else
    if not payload or string.sub(payload, 1, 1) ~= 'D' then return redis.error_reply('invalid user payload') end
    redis.call('PSETEX', KEYS[2], ttl, payload)
end
return 1
