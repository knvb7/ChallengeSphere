package com.ironside.challengesphere.config;

import jakarta.servlet.MultipartConfigElement;
import org.springframework.boot.web.servlet.MultipartConfigFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

@Configuration
public class MultipartUploadConfig {
    private static final long BACKEND_FALLBACK_MAX_FILE_MB = 8;
    private static final long BACKEND_FALLBACK_MAX_REQUEST_MB = 9;

    @Bean
    public MultipartConfigElement multipartConfigElement() {
        MultipartConfigFactory factory = new MultipartConfigFactory();
        factory.setMaxFileSize(DataSize.ofMegabytes(BACKEND_FALLBACK_MAX_FILE_MB));
        factory.setMaxRequestSize(DataSize.ofMegabytes(BACKEND_FALLBACK_MAX_REQUEST_MB));
        return factory.createMultipartConfig();
    }
}
