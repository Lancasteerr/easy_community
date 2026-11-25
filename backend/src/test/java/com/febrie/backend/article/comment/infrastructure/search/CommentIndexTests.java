package com.febrie.backend.article.comment.infrastructure.search;

import com.febrie.backend.article.comment.infrastructure.persistence.CommentIndexTasks;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.VersionType;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.bulk.OperationType;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.util.ObjectBuilder;
import com.febrie.backend.article.search.infrastructure.config.SearchProperties;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 验证写入要求 ES 严格比较外部版本，旧活动投影遇到更新的删除版本时可确认任务。 */
class CommentIndexTests {
    @Test
    void olderActiveDocumentUsesExternalVersionAndAcknowledgesOnlyVersionConflict() throws Exception {
        var es = mock(ElasticsearchClient.class);
        var properties = mock(SearchProperties.class);
        var active = new CommentIndexTasks.Document(5, 1, 100, true, 2, 4);
        var other = new CommentIndexTasks.Document(6, 1, 200, true, 3, 1);
        when(es.bulk(org.mockito.ArgumentMatchers.<Function<BulkRequest.Builder, ObjectBuilder<BulkRequest>>>any()))
                .thenAnswer(call -> {
                    Function<BulkRequest.Builder, ObjectBuilder<BulkRequest>> build = call.getArgument(0);
                    var operations = build.apply(new BulkRequest.Builder()).build().operations();
                    assertEquals(VersionType.External, operations.getFirst().index().versionType());
                    assertEquals(2L, operations.getFirst().index().version());
                    assertTrue(operations.getFirst().index().requireAlias());
                    return BulkResponse.of(b -> b.took(1).errors(true)
                            .items(failure("5", 409, "version_conflict_engine_exception"),
                                    failure("6", 429, "es_rejected_execution_exception")));
                });
        assertEquals(Set.of(5L), new CommentIndex(es, properties, "test").write(List.of(active, other)));
    }

    private static BulkResponseItem failure(String id, int status, String type) {
        return BulkResponseItem.of(r -> r.operationType(OperationType.Index).id(id).index("test-v1")
                .status(status).error(e -> e.type(type)));
    }
}
