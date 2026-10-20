package com.paiban.notification.service;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.StringTemplateResolver;

@Configuration
public class NotificationConfig {

    /**
     * Dedicated engine for string templates. Uses SpringEL (SpringTemplateEngine)
     * rather than OGNL so no extra expression dependency is required.
     */
    @Bean("notificationTemplateEngine")
    public TemplateEngine notificationTemplateEngine() {
        StringTemplateResolver resolver = new StringTemplateResolver();
        resolver.setTemplateMode(TemplateMode.TEXT);
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        return engine;
    }
}
