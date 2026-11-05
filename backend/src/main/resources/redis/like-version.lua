-- 三个Key是持久签发器，不是缓存；任何缺失、TTL或运行标识变化都拒绝签发。
local action = ARGV[1]
local info = redis.call('INFO', 'server', 'replication')
local run = string.match(info, 'run_id:([0-9a-f]+)')
local role = string.match(info, 'role:([a-z]+)')
local function fail(message) return redis.error_reply('LIKE_ALLOCATOR ' .. message) end
local function kind(key) return redis.call('TYPE', key).ok end
if (kind(KEYS[1]) ~= 'none' and kind(KEYS[1]) ~= 'string') or
   (kind(KEYS[2]) ~= 'none' and kind(KEYS[2]) ~= 'string') or
   (kind(KEYS[3]) ~= 'none' and kind(KEYS[3]) ~= 'hash') then return fail('invalid type') end
if not run or role ~= 'master' then return fail('not writable runtime') end
local epoch = redis.call('GET', KEYS[1])
local seq = redis.call('GET', KEYS[2])
local guardEpoch = redis.call('HGET', KEYS[3], 'epoch')
local guardRun = redis.call('HGET', KEYS[3], 'runId')
local state = redis.call('HGET', KEYS[3], 'state')
local function integer(value, limit)
    if not value or not string.match(value, '^%d+$') then return nil end
    local number = tonumber(value)
    if number < 0 or number >= limit or tostring(number) ~= value then return nil end
    return number
end
if action == 'STATUS' then return {epoch or '', seq or '', state or '', guardRun or '', run, guardEpoch or ''} end
if action == 'PAUSE' then
    -- 有条件暂停供失败清理使用，不能让迟到维护进程暂停新纪元。
    if ARGV[2] and ARGV[2] ~= '' and (epoch ~= ARGV[2] or guardRun ~= ARGV[3]) then return 0 end
    redis.call('HSET', KEYS[3], 'state', 'PAUSED')
    return 1
end
if action == 'INITIALIZE' then
    local target = integer(ARGV[2], 1048576)
    if not target or target < 1 or run ~= ARGV[3] then return fail('invalid recovery target') end
    local old = epoch and integer(epoch, 1048576) or nil
    local guarded = guardEpoch and integer(guardEpoch, 1048576) or nil
    if (epoch and not old) or (guardEpoch and not guarded) then return fail('corrupt epoch') end
    if (old and old >= target) or (guarded and guarded >= target) then return fail('cannot reset used epoch') end
    if state == 'READY' and guardRun == run then return fail('pause before recovery') end
    redis.call('SET', KEYS[1], ARGV[2])
    redis.call('SET', KEYS[2], '0')
    redis.call('HSET', KEYS[3], 'epoch', ARGV[2], 'runId', run, 'state', 'PAUSED')
    redis.call('PERSIST', KEYS[3])
    return 1
end
if not integer(epoch, 1048576) or not integer(seq, 8796093022208) or epoch ~= guardEpoch or run ~= guardRun then
    return fail('missing or mismatched guard')
end
for i = 1, 3 do if redis.call('PTTL', KEYS[i]) ~= -1 then return fail('allocator must not expire') end end
if action == 'ACTIVATE' then
    if epoch ~= ARGV[2] or run ~= ARGV[3] or state ~= 'PAUSED' then return fail('stale activation') end
    redis.call('HSET', KEYS[3], 'state', 'READY')
    return 1
end
if (action ~= 'ALLOCATE' and action ~= 'CHECK') or state ~= 'READY' then return fail('paused or unknown action') end
if tonumber(seq) + 1 >= 8796093022208 then return fail('sequence exhausted') end
if action == 'CHECK' then return 1 end
return {tonumber(epoch), redis.call('INCR', KEYS[2])}
