package dev.berth9.server.catalog;

import dev.berth9.server.store.Partner;
import dev.berth9.server.store.PartnerStore;
import dev.berth9.server.store.SpecStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/** Loads the approved v1 mapping spec for every demo partner that has none yet. */
@Component
@Order(1)
public class SpecSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SpecSeeder.class);

    private final PartnerStore partners;
    private final SpecStore specs;

    public SpecSeeder(PartnerStore partners, SpecStore specs) {
        this.partners = partners;
        this.specs = specs;
    }

    @Override
    public void run(ApplicationArguments args) {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        for (Partner partner : partners.all()) {
            if (specs.exists(partner.id())) {
                continue;
            }
            Resource resource = resolver.getResource("classpath:berth9/specs/" + partner.id() + ".json");
            if (resource.exists()) {
                specs.save(partner.id(), ConfigCatalog.read(resource), "initial onboarding", "system");
                log.info("seeded mapping spec v1 for {}", partner.id());
            }
        }
    }
}
