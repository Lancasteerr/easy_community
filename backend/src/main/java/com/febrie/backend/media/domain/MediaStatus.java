package com.febrie.backend.media.domain;

/** 失败上传仍为ISSUED，只持久化引用与清理需要的三个状态。 */
public enum MediaStatus { ISSUED, READY, DELETING }
