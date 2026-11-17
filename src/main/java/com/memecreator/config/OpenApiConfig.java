package com.memecreator.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI memeCreatorOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Meme Creator API")
                .version("0.0.1")
                .description("Turns a topic into memes: the AI gateway picks templates and writes captions, "
                        + "the app draws them onto the template images."));
    }
}
