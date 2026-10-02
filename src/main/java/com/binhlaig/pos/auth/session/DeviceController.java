package com.binhlaig.pos.auth.session;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/owner/shops/{shopId}/devices")
@PreAuthorize("hasRole('ADMIN')")
public class DeviceController {
    private final SessionService sessions;
    @GetMapping
    public List<SessionService.DeviceSession> list(@PathVariable Long shopId, @RequestHeader("Authorization") String authorization) {
        return sessions.devices(authorization.substring(7), shopId);
    }
    @DeleteMapping("/{deviceId}")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable Long shopId, @PathVariable String deviceId, @RequestHeader("Authorization") String authorization) {
        sessions.revokeDevice(authorization.substring(7), shopId, deviceId);
    }
}
