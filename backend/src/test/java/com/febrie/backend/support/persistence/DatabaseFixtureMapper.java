package com.febrie.backend.support.persistence;

import org.apache.ibatis.annotations.Param;

/** 独立于业务 Repository 的断言和清理，所有删除均限定本次测试用户。 */
public interface DatabaseFixtureMapper {
    int deleteIdentities(@Param("userId") long userId);
    int deleteUser(@Param("userId") long userId);
    int countIdentities(@Param("userId") long userId);
    int clearAvatar(@Param("userId") long userId);
    int deleteOwnedReferences(@Param("userId") long userId);
    int deleteOwnedFiles(@Param("userId") long userId);
    int countColumns(@Param("table") String table);
    int countReferences(@Param("fileId") byte[] fileId);
    int deleteFile(@Param("fileId") byte[] fileId);
    int expireFile(@Param("fileId") byte[] fileId);
    long authVersion(@Param("userId") long userId);
    int makeReady(@Param("fileId") byte[] fileId, @Param("size") long size);
}
