package com.febrie.backend.article.like;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 示例配置也是部署契约：拒绝重复键、遗留开关和无法被应用读取的RabbitMQ变量名。 */
class LikeEnvironmentExamplesTests {
    @Test void rabbitExamplesMatchApplicationBindingsWithoutDuplicateKeysOrFeatureFlags() throws Exception {
        var bindings=Map.of("RABBITMQ_HOST","spring.rabbitmq.host","RABBITMQ_PORT","spring.rabbitmq.port",
                "RABBITMQ_USERNAME","spring.rabbitmq.username","RABBITMQ_PASSWORD","spring.rabbitmq.password",
                "RABBITMQ_VHOST","spring.rabbitmq.virtual-host");
        var application=new Properties();
        try(var reader=Files.newBufferedReader(Path.of("src/main/resources/application.properties"),StandardCharsets.UTF_8)){application.load(reader);}
        for(String file:List.of(".env.dev.example",".env.prod.example")){
            var keys=new HashSet<String>();var sample=new Properties();
            for(String line:Files.readAllLines(Path.of(file),StandardCharsets.UTF_8)){
                String value=line.strip();if(value.isEmpty()||value.startsWith("#")){continue;}
                assertTrue(value.contains("="),file+": 无效配置行");
                assertTrue(keys.add(value.split("=",2)[0].strip()),file+": 配置键重复");
            }
            try(var reader=Files.newBufferedReader(Path.of(file),StandardCharsets.UTF_8)){sample.load(reader);}
            for(var entry:bindings.entrySet()){
                assertFalse(sample.getProperty(entry.getKey(),"").isBlank());
                assertTrue(application.getProperty(entry.getValue()).contains("${"+entry.getKey()+":"));
            }
            assertEquals("5672",sample.getProperty("RABBITMQ_PORT"));
            assertTrue(sample.getProperty("RABBITMQ_PASSWORD").startsWith("replace-with-"));
            assertTrue(Collections.disjoint(keys,List.of("LIKE_ENABLED","LIKE_WRITE_ENABLED","LIKE_CONSUMER_ENABLED")));
        }
    }
}
