package ru.practicum.ewm.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;

@Configuration(proxyBeanMethods = false)
public class TimeConfiguration implements WebMvcConfigurer {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }

    @Override
    public void addFormatters(FormatterRegistry registry) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")
                .withResolverStyle(ResolverStyle.STRICT);
        registry.addConverter(String.class, LocalDateTime.class,
                value -> value.isBlank() ? null : LocalDateTime.parse(value, formatter));
    }
}
