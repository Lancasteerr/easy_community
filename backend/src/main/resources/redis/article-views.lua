-- 日累计和实时总量共用hash tag。先检查全部可预见错误，再执行写入。
local mode = ARGV[1]
local field = ARGV[2]
local totalKey = KEYS[2] or KEYS[1]
local maximum = '9223372036854775807'

local function checkKey(key)
    local kind = redis.call('TYPE', key).ok
    if kind ~= 'none' and kind ~= 'hash' then error('浏览统计Key类型错误') end
end

local function checkCount(value)
    if value == false or value == nil then return end
    if not string.match(value, '^%d+$') or (#value > 1 and string.sub(value, 1, 1) == '0')
        or #value > #maximum or (#value == #maximum and value > maximum) then
        error('浏览计数不是非负BIGINT')
    end
end

local function below(left, right)
    return #left < #right or (#left == #right and left < right)
end

checkKey(totalKey)
if KEYS[2] then checkKey(KEYS[1]) end
local total = redis.call('HGET', totalKey, field)
checkCount(total)

if mode == 'INITIALIZE' then
    checkCount(ARGV[3])
    if not total then redis.call('HSET', totalKey, field, ARGV[3]) end
    return redis.call('HGET', totalKey, field)
end

local daily = redis.call('HGET', KEYS[1], field)
checkCount(daily)

if mode == 'COUNT' then
    if not total then
        -- 这是确认未写入的返回，调用方取得SQL基线后才能再次计数。
        if ARGV[3] == '' then return 'NEEDS_BASELINE' end
        checkCount(ARGV[3])
        total = ARGV[3]
    end
    if total == maximum or daily == maximum then error('浏览计数溢出') end
    if redis.call('HEXISTS', totalKey, field) == 0 then
        redis.call('HSET', totalKey, field, total)
    end
    redis.call('HINCRBY', KEYS[1], field, 1)
    redis.call('HINCRBY', totalKey, field, 1)
    if redis.call('TTL', KEYS[1]) == -1 then redis.call('EXPIRE', KEYS[1], 172800) end
    -- 不返回HINCRBY的Lua number，避免大整数经浮点转换损失精度。
    return redis.call('HGET', totalKey, field)
elseif mode == 'REPAIR' then
    checkCount(ARGV[3])
    if ARGV[4] ~= '' then checkCount(ARGV[4]) end
    -- 修复只提高下限；日字段消失后不重建，更不能把修复差额加入总量。
    if daily and ARGV[4] ~= '' and below(daily, ARGV[4]) then
        redis.call('HSET', KEYS[1], field, ARGV[4])
    end
    if not total or below(total, ARGV[3]) then redis.call('HSET', totalKey, field, ARGV[3]) end
    return redis.call('HGET', totalKey, field)
end
error('未知浏览统计操作')
