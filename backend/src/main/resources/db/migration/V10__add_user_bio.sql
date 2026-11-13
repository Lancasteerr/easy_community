-- 简介属于展示资料，不参与登录身份及认证版本管理；历史用户默认空简介。
ALTER TABLE users
    ADD COLUMN bio VARCHAR(50) NOT NULL DEFAULT '' COMMENT '最多50个Unicode码点的单行个人简介';
