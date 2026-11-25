package com.memecreator.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI memeCreatorOpenApi() {
        // "Authorize" in Swagger UI: paste an API key (e.g. an ADMIN key from ApiKeyTool) to try the protected APIs
        SecurityScheme apiKey = new SecurityScheme().type(SecurityScheme.Type.APIKEY).in(SecurityScheme.In.HEADER)
                .name("X-API-Key").description("Key issued with ApiKeyTool or POST /api/admin/api-keys");
        return new OpenAPI()
                .components(new Components().addSecuritySchemes("apiKey", apiKey))
                .addSecurityItem(new SecurityRequirement().addList("apiKey"))
                .info(new Info()
                        .title("Meme Creator API")
                        .version("0.0.1")
                        .description("Turns a topic into memes: the AI gateway picks templates and writes captions, "
                                + "the app draws them onto the template images."));
    }
}
