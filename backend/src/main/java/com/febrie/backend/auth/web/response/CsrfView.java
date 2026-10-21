package com.febrie.backend.auth.web.response;

/** 返回浏览器提交修改请求所需的 CSRF 令牌及请求头名称。 */
public record CsrfView(String token, String headerName) {
}
