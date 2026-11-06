package com.febrie.backend.article.content.application;

import com.febrie.backend.BackendApplication;
import com.febrie.backend.user.application.dto.UserActor;
import com.febrie.backend.user.domain.Role;
import java.util.List;
import org.springframework.boot.SpringApplication;

/** 仅测试子进程：真实COMMIT成功后立即退出，刻意不进入任何提交后缓存维护。 */
public final class ArticleCrashProcess {
    private ArticleCrashProcess() { }
    public static void main(String[] args) {
        long author = Long.parseLong(args[0]), article = Long.parseLong(args[1]);
        String testUrl = System.getenv("IT_DB_URL");
        if (testUrl == null || !testUrl.matches("jdbc:mysql://[^/]+/easy_community_[a-z0-9_]*test[a-z0-9_]*(?:\\?.*)?")) {
            throw new IllegalArgumentException("崩溃子进程只允许专用测试库");
        }
        var context = SpringApplication.run(BackendApplication.class, "--spring.profiles.active=test",
                "--spring.main.web-application-type=none", "--app.auth.redis-prefix=" + args[2]);
        var mapper = context.getBean(tools.jackson.databind.ObjectMapper.class);
        var content = context.getBean(ArticleContentRenderer.class).prepare("after-crash",
                mapper.readTree("{\"schemaVersion\":1,\"doc\":{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"after-crash\"}]}]}}"), null, List.of());
        context.getBean(ArticleCommitService.class).update(new UserActor(author, Role.USER, 1), article, content,
                new ArticleCommitService.Attempt());
        Runtime.getRuntime().halt(0);
    }
}
