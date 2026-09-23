package dev.berth9.server.api;

import dev.berth9.engine.config.Json;
import dev.berth9.server.catalog.ConfigCatalog;
import dev.berth9.server.delivery.ChaosState;
import dev.berth9.server.delivery.DeliveryService;
import dev.berth9.server.events.EventHub;
import dev.berth9.server.intake.DemoRunner;
import dev.berth9.server.processing.ExceptionService;
import dev.berth9.server.processing.MappingService;
import dev.berth9.server.processing.ProcessingService;
import dev.berth9.server.store.JobStore;
import dev.berth9.server.store.Partner;
import dev.berth9.server.store.PartnerStore;
import dev.berth9.server.store.RecordStore;
import dev.berth9.server.store.SpecStore;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/** REST API behind the operations console. */
@RestController
@RequestMapping("/api")
public class ConsoleController {

    private final PartnerStore partners;
    private final SpecStore specs;
    private final JobStore jobs;
    private final RecordStore records;
    private final ProcessingService processing;
    private final ExceptionService exceptions;
    private final MappingService mapping;
    private final DeliveryService delivery;
    private final ChaosState chaos;
    private final EventHub events;
    private final DemoRunner demo;
    private final ConfigCatalog catalog;

    public ConsoleController(PartnerStore partners, SpecStore specs, JobStore jobs, RecordStore records,
                             ProcessingService processing, ExceptionService exceptions, MappingService mapping,
                             DeliveryService delivery, ChaosState chaos, EventHub events, DemoRunner demo, ConfigCatalog catalog) {
        this.partners = partners;
        this.specs = specs;
        this.jobs = jobs;
        this.records = records;
        this.processing = processing;
        this.exceptions = exceptions;
        this.mapping = mapping;
        this.delivery = delivery;
        this.chaos = chaos;
        this.events = events;
        this.demo = demo;
        this.catalog = catalog;
    }

    // ------------------------------------------------------------------ overview & live events

    @GetMapping("/overview")
    public Map<String, Object> overview() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totals", jobs.totals());
        out.put("records", records.countsByStatus());
        out.put("partners", records.partnerStats());
        out.put("breaker", delivery.breakerState());
        out.put("chaos", chaos.snapshot());
        out.put("recentJobs", jobs.recent(8, null));
        out.put("liveClients", events.subscribers());
        return out;
    }

    @GetMapping(path = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events() {
        return events.subscribe();
    }

    @GetMapping("/events/recent")
    public List<Map<String, Object>> recentEvents(@RequestParam(defaultValue = "0") long after) {
        return events.recent(after);
    }

    // ------------------------------------------------------------------ partners & mapping specs

    @GetMapping("/partners")
    public List<Map<String, Object>> partners() {
        Map<String, String> last = jobs.lastReceivedByPartner();
        return partners.all().stream().map(p -> {
            Map<String, Object> m = partnerView(p);
            m.put("lastFileAt", last.get(p.id()));
            return m;
        }).toList();
    }

    @GetMapping("/partners/{id}")
    public Map<String, Object> partner(@PathVariable String id) {
        Partner p = partners.find(id).orElseThrow(() -> new NoSuchElementException("unknown partner " + id));
        Map<String, Object> m = partnerView(p);
        m.put("specs", specs.versions(p.id()));
        m.put("recentJobs", jobs.recent(20, p.id()));
        return m;
    }

    @PostMapping(path = "/partners/{id}/specs", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> saveSpec(@PathVariable String id, @RequestBody String body,
                                        @RequestParam(defaultValue = "saved from mapping studio") String note) {
        partners.find(id).orElseThrow(() -> new NoSuchElementException("unknown partner " + id));
        int version = specs.save(id, Json.parseObject(body), note, "operator");
        events.publish("spec.saved", Map.of("partnerId", id, "version", version));
        return Map.of("partnerId", id, "version", version);
    }

    @GetMapping("/schemas")
    public Map<String, Object> schemas() {
        Map<String, Object> out = new LinkedHashMap<>();
        catalog.schemas().keySet().forEach(name -> out.put(name, catalog.rawSchema(name)));
        return out;
    }

    // ------------------------------------------------------------------ files

    /** Console upload on behalf of a partner (the operator is authenticated by the operator token). */
    @PostMapping(path = "/partners/{id}/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ProcessingService.Result upload(@PathVariable String id, @RequestParam("file") MultipartFile file) throws IOException {
        return processing.process(id.toUpperCase(), file.getOriginalFilename(), file.getBytes(), "CONSOLE");
    }

    @GetMapping("/jobs")
    public List<JobStore.Job> jobs(@RequestParam(defaultValue = "50") int limit, @RequestParam(required = false) String partner) {
        return jobs.recent(Math.min(limit, 500), partner);
    }

    @GetMapping("/jobs/{id}")
    public Map<String, Object> job(@PathVariable long id) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("job", jobs.find(id).orElseThrow(() -> new NoSuchElementException("job " + id + " not found")));
        out.put("records", records.byJob(id, null, 2000));
        return out;
    }

    @GetMapping(path = "/jobs/{id}/ack", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> ack(@PathVariable long id) {
        return jobs.ack(id).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    // ------------------------------------------------------------------ exceptions queue

    @GetMapping("/exceptions")
    public List<RecordStore.StoredRecord> exceptionsQueue(@RequestParam(defaultValue = "200") int limit,
                                                          @RequestParam(required = false) String partner) {
        return records.exceptions(Math.min(limit, 1000), partner);
    }

    @GetMapping("/records/{id}")
    public Map<String, Object> record(@PathVariable long id) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("record", records.find(id).orElseThrow(() -> new NoSuchElementException("record " + id + " not found")));
        out.put("audit", records.auditTrail(id));
        return out;
    }

    @PutMapping(path = "/records/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public RecordStore.StoredRecord fix(@PathVariable long id, @RequestBody String body,
                                        @RequestHeader(value = "X-Operator-Name", defaultValue = "operator") String actor) {
        return exceptions.fix(id, Json.parseObject(body), actor);
    }

    @PostMapping("/records/{id}/dismiss")
    public RecordStore.StoredRecord dismiss(@PathVariable long id, @RequestParam(required = false) String reason,
                                            @RequestHeader(value = "X-Operator-Name", defaultValue = "operator") String actor) {
        return exceptions.dismiss(id, reason, actor);
    }

    @PostMapping("/records/{id}/replay")
    public RecordStore.StoredRecord replay(@PathVariable long id,
                                           @RequestHeader(value = "X-Operator-Name", defaultValue = "operator") String actor) {
        return exceptions.replay(id, actor);
    }

    // ------------------------------------------------------------------ mapping studio

    @PostMapping(path = "/mapping/suggest", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> suggest(@RequestParam("file") MultipartFile file,
                                       @RequestParam(defaultValue = "invoice-line") String schema,
                                       @RequestParam(required = false) String partner,
                                       @RequestParam(required = false) String record,
                                       @RequestParam(required = false) String locale) throws IOException {
        Partner p = partner == null || partner.isBlank() ? null : partners.find(partner.toUpperCase()).orElse(null);
        return mapping.suggest(schema, file.getOriginalFilename(), file.getBytes(), record, p, locale);
    }

    // ------------------------------------------------------------------ resilience demo

    @GetMapping("/chaos")
    public Map<String, Object> chaos() {
        Map<String, Object> out = new LinkedHashMap<>(chaos.snapshot());
        out.put("breaker", delivery.breakerState());
        return out;
    }

    @PostMapping(path = "/chaos", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> setChaos(@RequestBody String body) {
        Map<String, Object> in = Json.parseObject(body);
        chaos.set(in.get("erpDown") instanceof Boolean b ? b : null, number(in.get("latencyMs")), number(in.get("failRatePct")));
        events.publish("chaos.changed", chaos.snapshot());
        return chaos();
    }

    @PostMapping("/demo/run")
    public Map<String, Object> runDemo(@RequestParam(defaultValue = "1500") long gapMillis) throws IOException {
        return Map.of("started", demo.start(Math.max(200, Math.min(gapMillis, 10_000))));
    }

    private static Integer number(Object o) {
        return o instanceof Number n ? n.intValue() : null;
    }

    private static Map<String, Object> partnerView(Partner p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.id());
        m.put("name", p.name());
        m.put("country", p.country());
        m.put("city", p.city());
        m.put("role", p.role());
        m.put("channel", p.channel());
        m.put("format", p.format());
        m.put("document", p.document());
        m.put("schema", p.schema());
        m.put("currency", p.currency());
        m.put("gstin", p.gstin());
        m.put("ediId", p.ediId());
        m.put("locale", p.locale());
        m.put("expectedBy", p.expectedBy());
        return m;
    }
}
