package com.wms.users;

import java.util.Map;
import java.util.Set;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpStatus;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.util.WebUtils;

import com.wms.common.api.ApiException;
import com.wms.facility.Site;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Who may call what, and in which facility. Everything under /api needs someone signed in, except signing in itself. Device
 * requests and changes on the desktop also need the permission their controller or method
 * {@link Requires}; if it says none, they are refused.
 */
@Configuration
public class AccessConfig implements WebMvcConfigurer {

    private static final Set<String> READING = Set.of("GET", "HEAD", "OPTIONS");

    private final DeviceSessionService devices;
    private final DesktopSessionService desktop;

    public AccessConfig(DeviceSessionService devices, DesktopSessionService desktop) {
        this.devices = devices;
        this.desktop = desktop;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SameSite()).addPathPatterns("/api/**");
        registry.addInterceptor(new Device()).addPathPatterns("/api/rf/**").excludePathPatterns("/api/rf/session", "/api/rf/session/**");
        registry.addInterceptor(new Desktop()).addPathPatterns("/api/**")
                .excludePathPatterns("/api/rf/**", "/api/session", "/api/session/**", "/api/health");
    }

    /** Browsers say when another site made them send a request; nothing is changed for those. */
    private static final class SameSite implements HandlerInterceptor {
        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
            String from = request.getHeader("Sec-Fetch-Site");
            if (!READING.contains(request.getMethod()) && from != null && !from.equals("same-origin") && !from.equals("none")) {
                throw new ApiException(HttpStatus.FORBIDDEN, "This request came from another site");
            }
            return true;
        }
    }

    private final class Device implements HandlerInterceptor {
        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
            DeviceUser user = devices.find(cookie(request, DeviceSessionController.COOKIE)).orElseThrow(AccessConfig::signInFirst);
            require(handler, user.permissions(), true);
            request.setAttribute(DeviceUser.ATTRIBUTE, user);
            Site.enter(user.facility().id());
            return true;
        }

        @Override
        public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception e) {
            Site.leave();
        }
    }

    private final class Desktop implements HandlerInterceptor {
        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
            // Already known when the server itself says who this is; a client can't set attributes.
            DesktopUser user = request.getAttribute(DesktopUser.ATTRIBUTE) instanceof DesktopUser known ? known
                    : desktop.find(cookie(request, DesktopSessionController.COOKIE)).orElseThrow(AccessConfig::signInFirst);
            if (user.mustChangePassword()) {
                throw new ApiException(HttpStatus.FORBIDDEN, "Choose a new password first", Map.of("mustChangePassword", "choose a new password"));
            }
            require(handler, user.permissions(), !READING.contains(request.getMethod()));
            request.setAttribute(DesktopUser.ATTRIBUTE, user);
            Site.enter(user.facility().id());
            return true;
        }

        @Override
        public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception e) {
            Site.leave();
        }
    }

    /** @param always refuse if no permission is named */
    private static void require(Object handler, Set<Permission> held, boolean always) {
        if (!(handler instanceof HandlerMethod method)) {
            return; // nothing there to protect; it will be not found
        }
        Requires requires = required(method);
        if (requires == null ? always : !held.contains(requires.value())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "You don't have permission to do that");
        }
    }

    static Requires required(HandlerMethod method) {
        Requires onMethod = AnnotatedElementUtils.findMergedAnnotation(method.getMethod(), Requires.class);
        return onMethod != null ? onMethod : AnnotatedElementUtils.findMergedAnnotation(method.getBeanType(), Requires.class);
    }

    private static String cookie(HttpServletRequest request, String name) {
        Cookie cookie = WebUtils.getCookie(request, name);
        return cookie == null ? null : cookie.getValue();
    }

    private static ApiException signInFirst() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "Sign in to continue");
    }
}
