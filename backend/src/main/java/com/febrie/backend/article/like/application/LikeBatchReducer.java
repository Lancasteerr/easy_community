package com.febrie.backend.article.like.application;

import com.febrie.backend.article.like.domain.LikeCommand;
import java.util.*;

/** 归并单篇文章的有界批次；输入下标用于关联全部原始投递。 */
public final class LikeBatchReducer {
    public Reduction reduce(List<LikeCommand> commands) {
        Map<Long, List<Integer>> indices = new LinkedHashMap<>();
        Map<Long, Map<Long, Boolean>> versions = new HashMap<>();
        Map<Long, LikeCommand> winners = new LinkedHashMap<>();
        Set<Long> conflicts = new HashSet<>();
        for (int i = 0; i < commands.size(); i++) {
            var command = commands.get(i);
            long user = command.userId();
            indices.computeIfAbsent(user, ignored -> new ArrayList<>()).add(i);
            Boolean previous = versions.computeIfAbsent(user, ignored -> new HashMap<>())
                    .putIfAbsent(command.number(), command.desiredLiked());
            if (previous != null && previous != command.desiredLiked().booleanValue()) { conflicts.add(user); }
            winners.merge(user, command, (old, next) -> next.number() > old.number() ? next : old);
        }
        List<LikeCommand> merged = new ArrayList<>();
        List<Integer> accepted = new ArrayList<>(), rejected = new ArrayList<>();
        indices.forEach((user, positions) -> {
            if (conflicts.contains(user)) { rejected.addAll(positions); }
            else { merged.add(winners.get(user)); accepted.addAll(positions); }
        });
        return new Reduction(merged, accepted, rejected);
    }

    /** 合法下标包含被高版本覆盖的消息；冲突关系的所有消息一并隔离。 */
    public record Reduction(List<LikeCommand> commands, List<Integer> accepted, List<Integer> rejected) {
        public Reduction {
            commands = List.copyOf(commands);
            accepted = List.copyOf(accepted);
            rejected = List.copyOf(rejected);
        }
    }
}
