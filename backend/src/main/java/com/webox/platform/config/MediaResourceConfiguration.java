package com.webox.platform.config;

import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class MediaResourceConfiguration implements WebMvcConfigurer {
    private final Path mediaRoot;

    public MediaResourceConfiguration(@Value("${webox.media.root:./uploads}") String mediaRoot) {
        this.mediaRoot = Path.of(mediaRoot).toAbsolutePath().normalize();
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String location = mediaRoot.toUri().toString();
        registry.addResourceHandler("/media/images/**").addResourceLocations(location.endsWith("/") ? location : location + "/");
        Path seedRoot = mediaRoot.resolve("seed").normalize();
        if (seedRoot.startsWith(mediaRoot)) {
            String seedLocation = seedRoot.toUri().toString();
            registry.addResourceHandler("/media/seed/**").addResourceLocations(seedLocation.endsWith("/") ? seedLocation : seedLocation + "/");
        }
    }
}
