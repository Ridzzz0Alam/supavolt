package dev.supavolt.api.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Clock;
import java.util.List;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.method.HandlerTypePredicate;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.EnumFeature;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    /** Every controller lives under /api. The realtime endpoint stays at /realtime, outside it. */
    public static final String API_PREFIX = "/api";

    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        // Our own REST controllers only: springdoc's are REST controllers too, with paths of their own.
        configurer.addPathPrefix(API_PREFIX, HandlerTypePredicate.forAnnotation(RestController.class)
                .and(HandlerTypePredicate.forBasePackage("dev.supavolt.api")));
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * camelCase with lowercase string enums matches what frontend/lib/types.ts expects. Null
     * properties are left out, but nulls inside rows (maps) are kept: a NULL column is data.
     */
    @Bean
    JsonMapperBuilderCustomizer jsonConventions() {
        return builder -> builder
                .enable(EnumFeature.WRITE_ENUMS_TO_LOWERCASE)
                .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
                .changeDefaultPropertyInclusion(inclusion -> inclusion
                        .withValueInclusion(JsonInclude.Include.NON_NULL)
                        .withContentInclusion(JsonInclude.Include.ALWAYS));
    }

    /** The dashboard origin only. AllowCredentials forbids a wildcard origin. */
    @Bean
    CorsConfigurationSource corsConfigurationSource(SupavoltProperties properties) {
        var cors = new CorsConfiguration();
        cors.setAllowedOrigins(List.of(properties.web().url()));
        cors.setAllowedHeaders(List.of("*"));
        cors.setAllowedMethods(List.of("*"));
        cors.setAllowCredentials(true);

        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);
        return source;
    }
}
