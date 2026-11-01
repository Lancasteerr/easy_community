-- 固定四个同槽Key：锁、隐藏墓碑、详情、列表。先校验再修改，避免脚本错误形成部分写入。
local token, action, payload = ARGV[1], ARGV[2], ARGV[3]
local ttl = tonumber(ARGV[4])
local allowed = {PUBLISH_DETAIL=true, PUBLISH_LIST=true, MISSING=true, INVALIDATE=true, HIDE=true, UNLOCK=true}
if #KEYS ~= 4 or not token or #token == 0 or not allowed[action]
    or not ttl or ttl < 1 or ttl > 300000 or ttl ~= math.floor(ttl) then
    return redis.error_reply('invalid article cache arguments')
end
for i = 1, 4 do
    local kind = redis.call('TYPE', KEYS[i]).ok
    if kind ~= 'none' and kind ~= 'string' then return redis.error_reply('invalid article cache key type') end
end
if redis.call('GET', KEYS[1]) ~= token then return 0 end
if action == 'UNLOCK' then
    redis.call('DEL', KEYS[1])
elseif action == 'INVALIDATE' then
    redis.call('DEL', KEYS[3], KEYS[4])
elseif action == 'HIDE' then
    redis.call('SET', KEYS[2], '1')
    redis.call('PSETEX', KEYS[3], ttl, 'M')
    redis.call('PSETEX', KEYS[4], ttl, 'M')
elseif action == 'MISSING' then
    redis.call('PSETEX', KEYS[3], ttl, 'M')
    redis.call('PSETEX', KEYS[4], ttl, 'M')
else
    if redis.call('EXISTS', KEYS[2]) == 1 then return 0 end
    if not payload or string.sub(payload, 1, 1) ~= 'D' then return redis.error_reply('invalid article payload') end
    local target = action == 'PUBLISH_DETAIL' and KEYS[3] or KEYS[4]
    redis.call('PSETEX', target, ttl, payload)
end
return 1
