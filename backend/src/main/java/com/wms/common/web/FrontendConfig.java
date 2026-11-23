package com.wms.common.web;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * Serves the built React app, when it is packaged in (classpath:/static, as the Docker image does), so one server answers
 * both the screens and the API at one address. Any path that isn't a file or the API is a screen of the app, so it gets
 * index.html and the app shows the right one. In development Vite serves the app instead, and nothing is packaged.
 */
@Configuration
public class FrontendConfig implements WebMvcConfigurer {

    private static final String STATIC = "classpath:/static/";

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // Built files have their content's hash in their name, so they can be kept for good.
        registry.addResourceHandler("/assets/**").addResourceLocations(STATIC + "assets/")
                .setCacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic().immutable());
        registry.addResourceHandler("/**").addResourceLocations(STATIC).setCacheControl(CacheControl.noCache())
                .resourceChain(false).addResolver(new App());
    }

    /** A file if there is one; otherwise the app, except for the API, which says it doesn't know the path. */
    private static final class App extends PathResourceResolver {
        @Override
        protected Resource getResource(String path, Resource location) throws IOException {
            Resource file = location.createRelative(path);
            if (file.isReadable() && file.exists()) {
                return file;
            }
            if (path.equals("api") || path.startsWith("api/") || path.startsWith("assets/")) {
                return null;
            }
            Resource app = new ClassPathResource("static/index.html");
            return app.exists() ? app : null;
        }
    }
}
