package com.binhlaig.pos.modules.product;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.Supplier;

@Service @RequiredArgsConstructor
public class StockRequestService {
    private final EntityManager entityManager;
    private final StockRequestRepository repository;
    private final ObjectMapper mapper;

    @Transactional(propagation = Propagation.MANDATORY)
    public <T> T execute(Long shopId, String requestId, String channel, Object payload,
                         Class<T> responseType, Supplier<T> action) {
        if (shopId == null || requestId == null || !requestId.matches("[A-Za-z0-9_-]{1,100}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "requestId is required (1-100 letters, digits, underscore or hyphen)");
        }
        // Serializes retries even before a row exists; released on commit/rollback.
        entityManager.createNativeQuery("select 1 from pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .setParameter("key", "stock-request:" + shopId + ":" + requestId).getSingleResult();
        try {
            String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(mapper.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8)));
            var existing = repository.findByShopIdAndRequestKey(shopId, requestId);
            if (existing.isPresent()) {
                StockRequest old = existing.get();
                if (!old.getChannel().equals(channel) || !old.getFingerprint().equals(fingerprint)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "requestId already used for a different operation or payload");
                }
                return mapper.readValue(old.getResponseJson(), responseType);
            }
            T result = action.get();
            StockRequest record = new StockRequest();
            record.setShopId(shopId);
            record.setRequestKey(requestId);
            record.setChannel(channel);
            record.setFingerprint(fingerprint);
            record.setResponseJson(mapper.writeValueAsString(result));
            repository.save(record);
            return result;
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Unable to encode stock request", e);
        }
    }
}
