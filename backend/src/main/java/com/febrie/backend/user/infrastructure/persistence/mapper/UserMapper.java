package com.febrie.backend.user.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.febrie.backend.user.domain.Role;
import com.febrie.backend.user.infrastructure.persistence.entity.AccountRow;
import com.febrie.backend.user.infrastructure.persistence.entity.UserEntity;
import org.apache.ibatis.annotations.Param;

/** 常规操作复用 BaseMapper；认证锁与安全版本更新由 XML 明确表达。 */
public interface UserMapper extends BaseMapper<UserEntity> {
    AccountRow findAccountByUsername(@Param("username") String username);
    AccountRow lockAccountById(@Param("userId") long userId);
    UserEntity lockProfile(@Param("userId") long userId);
    int incrementAuthVersion(@Param("userId") long userId);
    int softDelete(@Param("userId") long userId);
    int updateRole(@Param("userId") long userId, @Param("role") Role role);
}
