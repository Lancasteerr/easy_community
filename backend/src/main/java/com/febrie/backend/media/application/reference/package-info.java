/**
 * 在调用者显式READ_COMMITTED事务内维护文件引用；先锁业务行，再按文件ID排序加锁。
 */
package com.febrie.backend.media.application.reference;
