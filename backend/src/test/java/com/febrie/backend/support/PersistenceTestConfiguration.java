package com.febrie.backend.support;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.test.context.TestConfiguration;

/** 测试造数与清理专用 Mapper；由测试显式导入，不参与生产扫描。 */
@TestConfiguration(proxyBeanMethods = false)
@MapperScan(basePackages = "com.febrie.backend.support.persistence", sqlSessionTemplateRef = "sqlSessionTemplate")
public class PersistenceTestConfiguration { }
