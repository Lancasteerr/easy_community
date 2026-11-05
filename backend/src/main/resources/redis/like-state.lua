-- 批量CAS先检查所有字段，再写入，避免脚本运行时错误造成部分更新。
local phase, ttl = ARGV[1], tonumber(ARGV[2])
if (phase ~= 'P' and phase ~= 'C') or not ttl or ttl < 1 or (#ARGV-2)%3 ~= 0 then return redis.error_reply('LIKE_CACHE invalid arguments') end
local kind = redis.call('TYPE', KEYS[1]).ok
if kind ~= 'none' and kind ~= 'hash' then return redis.error_reply('LIKE_CACHE invalid type') end
local fresh = kind == 'none'
if not fresh and redis.call('PTTL', KEYS[1]) == -1 then return redis.error_reply('LIKE_CACHE missing expiry') end
local selected, updates = {}, {}
for i=3,#ARGV,3 do
    local field, version, liked = ARGV[i], ARGV[i+1], ARGV[i+2]
    if #version ~= 19 or not string.match(version, '^%d+$') or (liked ~= '0' and liked ~= '1') then return redis.error_reply('LIKE_CACHE invalid state') end
    local incoming = version .. '|' .. liked .. '|' .. phase
    local current = redis.call('HGET', KEYS[1], field)
    if current then
        local cv, cl, cp = string.match(current, '^(%d+)|([01])|([PC])$')
        if not cv or #cv ~= 19 then return redis.error_reply('LIKE_CACHE corrupt state') end
        if cv == version and cl ~= liked then return redis.error_reply('LIKE_CACHE version conflict') end
        if version > cv or (version == cv and phase == 'C' and cp == 'P') then
            updates[#updates+1] = {field, incoming}; current = incoming
        end
    else updates[#updates+1] = {field, incoming}; current = incoming end
    selected[#selected+1] = current
end
for _, update in ipairs(updates) do redis.call('HSET', KEYS[1], update[1], update[2]) end
-- 固定寿命；任何访问、迟到消息或其他字段更新都不能为整个用户Hash续命。
if fresh and #updates > 0 then redis.call('EXPIRE', KEYS[1], ttl) end
return selected
