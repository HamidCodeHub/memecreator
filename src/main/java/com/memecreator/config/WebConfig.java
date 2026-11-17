package com.memecreator.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;

/**
 * Serves generated memes from the output directory under /generated/**.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final MemeProperties props;

    public WebConfig(MemeProperties props) {
        this.props = props;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String location = Path.of(props.outputDir()).toAbsolutePath().toUri().toString();
        if (!location.endsWith("/")) {
            location += "/";
        }
        registry.addResourceHandler("/generated/**").addResourceLocations(location);
    }
}
