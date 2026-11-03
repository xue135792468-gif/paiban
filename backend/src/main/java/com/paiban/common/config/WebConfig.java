package com.paiban.common.config;

import java.io.IOException;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    /**
     * Public uploads (the school logo). Recipe samples live under the same root but are
     * private inputs to the import service, so they are not served here.
     */
    private static final String PRIVATE_IMPORT_RECIPES = "import-recipes/";

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations("file:./uploads/")
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location)
                            throws IOException {
                        if (resourcePath.startsWith(PRIVATE_IMPORT_RECIPES)) {
                            return null;
                        }
                        return super.getResource(resourcePath, location);
                    }
                });
    }
}
