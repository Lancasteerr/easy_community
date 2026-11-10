package com.febrie.backend.user.application;

import com.febrie.backend.user.application.dto.UserBasicData;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** 展示资料入口独立于认证；本阶段先通过批量主库读取组装。 */
@Service
@Transactional(propagation = Propagation.NEVER)
public class UserBasicQueryService {
    private final UserBasicReader reader;
    public UserBasicQueryService(UserBasicReader reader) { this.reader = reader; }
    public Optional<UserBasicData> get(long id) { return Optional.ofNullable(get(List.of(id)).get(id)); }
    public Map<Long, UserBasicData> get(Collection<Long> ids) {
        var ordered = new ArrayList<>(new LinkedHashSet<>(ids));
        Map<Long, UserBasicData> result = new HashMap<>();
        for (int start = 0; start < ordered.size(); start += 100) {
            result.putAll(reader.load(ordered.subList(start, Math.min(start + 100, ordered.size()))));
        }
        return result;
    }
}
