package dev.berth9.server.config;

import dev.berth9.engine.format.SourceFormat;
import dev.berth9.engine.read.RecordReaders;
import dev.berth9.engine.read.TabularRecordReader;
import dev.berth9.engine.rules.RuleEngine;
import dev.berth9.engine.suggest.MappingSuggester;
import dev.berth9.engine.transform.TransformRegistry;
import dev.berth9.server.processing.XlsxRowSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Wires the dependency-free engine into Spring. */
@Configuration
public class EngineConfig implements WebMvcConfigurer {

    @Bean
    public TransformRegistry transformRegistry() {
        return TransformRegistry.standard();
    }

    @Bean
    public RuleEngine ruleEngine() {
        return new RuleEngine();
    }

    @Bean
    public MappingSuggester mappingSuggester() {
        return new MappingSuggester();
    }

    /** Engine readers plus spreadsheet support, which is the one format that needs a library (Apache POI). */
    @Bean
    public RecordReaders recordReaders() {
        return RecordReaders.withDefaults()
                .register(SourceFormat.XLSX, (in, options) -> new TabularRecordReader(new XlsxRowSource(in, options.sheet()), options.headerRow()));
    }

    /** The console can be served from anywhere (Vercel, localhost dev server), so the API allows cross-origin reads. */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**").allowedOriginPatterns("*").allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*");
    }
}
