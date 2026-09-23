package dev.berth9.server.api;

import dev.berth9.server.processing.ProcessingService;
import dev.berth9.server.security.PartnerSignatures;
import dev.berth9.server.store.Partner;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Partner-facing API push channel: {@code POST /api/inbound/{partnerId}} with the raw document as the body
 * (JSON, cXML, CSV, X12...) and HMAC-signed headers. Returns 202 with the job summary; the same pipeline
 * as SFTP runs behind it.
 */
@RestController
@RequestMapping("/api/inbound")
public class InboundController {

    private final PartnerSignatures signatures;
    private final ProcessingService processing;

    public InboundController(PartnerSignatures signatures, ProcessingService processing) {
        this.signatures = signatures;
        this.processing = processing;
    }

    @PostMapping("/{partnerId}")
    public ResponseEntity<ProcessingService.Result> push(@PathVariable String partnerId,
                                                         @RequestHeader(value = "X-Api-Key", required = false) String apiKey,
                                                         @RequestHeader(value = "X-Timestamp", required = false) String timestamp,
                                                         @RequestHeader(value = "X-Signature", required = false) String signature,
                                                         @RequestHeader(value = "X-File-Name", defaultValue = "api-push") String fileName,
                                                         @RequestBody byte[] body) {
        Partner partner = signatures.verify(partnerId, apiKey, timestamp, signature, body);
        ProcessingService.Result result = processing.process(partner.id(), fileName, body, "API");
        return ResponseEntity.status(result.duplicate() ? HttpStatus.OK : HttpStatus.ACCEPTED).body(result);
    }
}
