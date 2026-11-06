package com.febrie.backend.media.domain;

/** 未发布文件保持ISSUED；提交结果未知时也可能已READY，两者均按引用和期限回收。 */
public enum MediaStatus { ISSUED, READY, DELETING }
