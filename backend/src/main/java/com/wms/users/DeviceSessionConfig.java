package com.wms.users;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.util.WebUtils;

import com.wms.common.api.ApiException;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Every mobile device request except sign-in itself needs a signed-in user. */
@Configuration
public class DeviceSessionConfig implements WebMvcConfigurer {

    private final DeviceSessionService sessions;

    public DeviceSessionConfig(DeviceSessionService sessions) {
        this.sessions = sessions;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new RequireSignIn())
                .addPathPatterns("/api/rf/**")
                .excludePathPatterns("/api/rf/session");
    }

    private final class RequireSignIn implements HandlerInterceptor {
        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
            Cookie cookie = WebUtils.getCookie(request, DeviceSessionController.COOKIE);
            DeviceUser user = sessions.find(cookie == null ? null : cookie.getValue())
                    .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Sign in to continue"));
            request.setAttribute(DeviceUser.ATTRIBUTE, user);
            return true;
        }
    }
}
