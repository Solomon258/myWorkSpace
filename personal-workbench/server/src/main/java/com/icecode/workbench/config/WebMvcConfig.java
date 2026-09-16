package com.icecode.workbench.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.icecode.workbench.auth.LoginInterceptor;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final LoginInterceptor loginInterceptor;

    public WebMvcConfig(LoginInterceptor loginInterceptor) {
        this.loginInterceptor = loginInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(loginInterceptor)
                .addPathPatterns("/api/v1/**")
                .excludePathPatterns("/api/v1/auth/**",
                        // 启动器本机调用：应用只绑定 127.0.0.1，外部无法到达这两个端点
                        "/api/v1/system/shutdown", "/api/v1/system/backup",
                        // 本机 Agent 投递收藏：Hermes / OpenClaw 收到微信里的文章链接后转身调这里。
                        // 免登录的理由和上面两条一样 —— 容器端口映射写死了回环地址
                        // （127.0.0.1:18080:8080），局域网里其它设备根本连不上，调用方只能是本机进程。
                        // ⚠️ 前提是这个绑定不要改。一旦把工作台暴露到局域网或公网，
                        //    这个端点会跟着一起暴露，届时必须先加鉴权再开放。
                        "/api/v1/collect/agent");
    }
}
