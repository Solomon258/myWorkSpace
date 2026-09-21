package com.icecode.workbench.settings;

import javax.validation.constraints.Size;

/**
 * 「得到登录 Cookie」的保存请求。
 *
 * <p>长度上限放到 4000：得到的 Cookie 串实测数百字符，但用户从开发者工具里
 * 整行复制时常常连带页面其它 Cookie，给足余量比让他在长度上踩坑划算。
 * 校验注解必须自带中文 message（见项目约定），否则会透出 Hibernate 的英文默认文案。
 */
public class DedaoCookieRequest {

    @Size(max = 4000, message = "Cookie 不能超过 4000 字")
    private String cookie;

    public String getCookie() { return cookie; }
    public void setCookie(String cookie) { this.cookie = cookie; }
}
