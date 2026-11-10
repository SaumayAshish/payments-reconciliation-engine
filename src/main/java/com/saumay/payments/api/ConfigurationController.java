package com.saumay.payments.api;

import com.saumay.payments.application.ApprovalConfigurationStore;
import com.saumay.payments.domain.approval.ApprovalThreshold;
import java.time.Clock;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Appends complete approval-threshold configurations. */
@RestController
@RequestMapping("/api/v1/configuration")
public class ConfigurationController {

    private final ApprovalConfigurationStore configurations;
    private final ActorContext actors;
    private final Clock clock;

    public ConfigurationController(ApprovalConfigurationStore configurations, ActorContext actors, Clock clock) {
        this.configurations = configurations;
        this.actors = actors;
        this.clock = clock;
    }

    public record UpdateRequest(Map<String, Long> perCurrencyMinor) {
    }

    @PutMapping
    public ApprovalConfigurationStore.Snapshot update(
            @RequestHeader(name = ExceptionController.ACTOR_HEADER, required = false) String header,
            @RequestBody UpdateRequest body) {
        Actor actor = actors.resolve(header);
        actor.require(Set.of(Role.ADMIN));

        ApprovalThreshold threshold;
        try {
            if (body == null || body.perCurrencyMinor() == null) {
                throw new IllegalArgumentException("perCurrencyMinor is required");
            }
            threshold = new ApprovalThreshold(ApprovalThreshold.DEFAULT_MINOR, body.perCurrencyMinor());
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "REQUEST_INVALID",
                    "perCurrencyMinor must map upper-case currency codes to non-negative minor-unit amounts");
        }
        return configurations.append(threshold, actor.id(), clock.instant());
    }
}