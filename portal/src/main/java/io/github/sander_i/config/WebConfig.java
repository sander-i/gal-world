/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.config;

import io.github.sander_i.i18n.LocaleFilter;
import io.github.sander_i.security.WriteSecurityInterceptor;
import io.github.sander_i.web.StreamWsBridge;
import jakarta.servlet.ServletContext;
import jakarta.websocket.server.ServerContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.context.ServletContextAware;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * MVC 拦截器与串流 WebSocket 端点注册。
 *
 * WebSocket 不用 Spring 的 WebSocketHandler：那需要 spring-websocket 依赖，
 * 而本项目是离线构建，本地仓库里没有。Tomcat 自带的 jakarta.websocket
 * 实现足够，直接在 ServerContainer 上注册端点。
 *
 * 注册时机用 ApplicationReadyEvent 而不是 ServletContextInitializer：
 * 后者在容器初始化阶段执行，那时 Tomcat 还没把 ServerContainer 放进
 * ServletContext，取到的会是 null。
 */
@org.springframework.context.annotation.Configuration
public class WebConfig implements WebMvcConfigurer, ServletContextAware {

    private static final Logger log = LoggerFactory.getLogger(WebConfig.class);

    /** Tomcat 放进 ServletContext 的标准属性名 */
    private static final String WS_CONTAINER_ATTR = "jakarta.websocket.server.ServerContainer";

    private final WriteSecurityInterceptor writeSecurity;
    private final LocaleFilter localeFilter;
    private ServletContext servletContext;

    public WebConfig(WriteSecurityInterceptor writeSecurity, LocaleFilter localeFilter) {
        this.writeSecurity = writeSecurity;
        this.localeFilter = localeFilter;
    }

    /**
     * 语言过滤器要在最外层：任何异常处理与鉴权拒绝都要用请求的语言回消息。
     * 顺序取最低值，保证它在写接口令牌校验之前执行。
     */
    @Bean
    public FilterRegistrationBean<LocaleFilter> localeFilterRegistration() {
        FilterRegistrationBean<LocaleFilter> reg = new FilterRegistrationBean<>(localeFilter);
        reg.setOrder(Integer.MIN_VALUE);
        reg.addUrlPatterns("/*");
        return reg;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(writeSecurity).addPathPatterns("/api/**");
    }

    @Override
    public void setServletContext(ServletContext ctx) {
        this.servletContext = ctx;
    }

    /** 应用就绪后再注册：此时 Tomcat 的 WebSocket 容器已挂到 ServletContext 上 */
    @EventListener(ApplicationReadyEvent.class)
    public void registerStreamWs() {
        if (servletContext == null) {
            log.warn("拿不到 ServletContext，串流 WebSocket 未注册");
            return;
        }
        Object attr = servletContext.getAttribute(WS_CONTAINER_ATTR);
        if (!(attr instanceof ServerContainer container)) {
            log.warn("Servlet 容器未提供 WebSocket 支持，串流代理不可用");
            return;
        }
        try {
            container.addEndpoint(StreamWsBridge.class);
            log.info("串流 WebSocket 端点已注册");
        } catch (Exception e) {
            log.warn("注册串流 WebSocket 端点失败: {}", e.toString());
        }
    }
}
