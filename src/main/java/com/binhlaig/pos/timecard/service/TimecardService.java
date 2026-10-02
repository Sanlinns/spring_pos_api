//
//
//package com.binhlaig.pos.timecard.service;
//
//import com.binhlaig.pos.auth.JwtService;
//import com.binhlaig.pos.staff.entity.Staff;
//import com.binhlaig.pos.staff.repository.StaffRepository;
//import com.binhlaig.pos.timecard.dto.ClockInRequest;
//import com.binhlaig.pos.timecard.dto.TimecardShiftResponse;
//import com.binhlaig.pos.timecard.entity.TimecardBreak;
//import com.binhlaig.pos.timecard.entity.TimecardShift;
//import com.binhlaig.pos.timecard.repository.TimecardBreakRepository;
//import com.binhlaig.pos.timecard.repository.TimecardShiftRepository;
//import com.binhlaig.pos.user.User;
//import com.binhlaig.pos.user.UserRepository;
//import lombok.RequiredArgsConstructor;
//import org.springframework.stereotype.Service;
//import org.springframework.transaction.annotation.Transactional;
//
//import java.time.LocalDate;
//import java.time.LocalDateTime;
//import java.util.ArrayList;
//import java.util.List;
//
//@Service
//@RequiredArgsConstructor
//public class TimecardService {
//
//    private final TimecardShiftRepository shiftRepository;
//    private final TimecardBreakRepository breakRepository;
//    private final StaffRepository staffRepository;
//    private final UserRepository userRepository;
//    private final JwtService jwtService;
//
//    @Transactional(readOnly = true)
//    public List<TimecardShiftResponse> getShifts(Long employeeId, Long shopId) {
//        List<TimecardShift> shifts;
//
//        if (employeeId != null && shopId != null) {
//            shifts = shiftRepository.findByEmployeeIdAndShopIdWithBreaksOrderByClockInTimeDesc(employeeId, shopId);
//        } else if (employeeId != null) {
//            shifts = shiftRepository.findByEmployeeIdWithBreaksOrderByClockInTimeDesc(employeeId);
//        } else if (shopId != null) {
//            shifts = shiftRepository.findByShopIdWithBreaksOrderByClockInTimeDesc(shopId);
//        } else {
//            shifts = shiftRepository.findAllWithBreaksOrderByClockInTimeDesc();
//        }
//
//        return shifts.stream().map(this::toResponse).toList();
//    }
//
//    @Transactional(readOnly = true)
//    public List<TimecardShiftResponse> getMyShifts(String authorizationHeader) {
//        SessionInfo sessionInfo = extractSessionInfo(authorizationHeader);
//
//        Staff staff = resolveStaffFromSession(sessionInfo);
//        List<TimecardShift> shifts = shiftRepository
//                .findByEmployeeIdAndShopIdWithBreaksOrderByClockInTimeDesc(
//                        staff.getId(),
//                        sessionInfo.shopId()
//                );
//
//        return shifts.stream().map(this::toResponse).toList();
//    }
//
//    @Transactional
//    public TimecardShiftResponse clockIn(ClockInRequest req, String authorizationHeader) {
//        SessionInfo sessionInfo = extractSessionInfo(authorizationHeader);
//
//        Staff staff = resolveStaffFromSession(sessionInfo);
//
//        boolean alreadyOpen = shiftRepository.existsByEmployeeIdAndWorkDateAndClockOutTimeIsNull(
//                staff.getId(),
//                LocalDate.now()
//        );
//
//        if (alreadyOpen) {
//            throw new RuntimeException("Employee already clocked in");
//        }
//
//        TimecardShift shift = TimecardShift.builder()
//                .employeeId(staff.getId())
//                .shopId(sessionInfo.shopId())
//                .shopCode(sessionInfo.shopCode())
//                .workDate(LocalDate.now())
//                .clockInTime(LocalDateTime.now())
//                .note(req.getNote())
//                .build();
//
//        if (shift.getBreaks() == null) {
//            shift.setBreaks(new ArrayList<>());
//        }
//
//        return toResponse(shiftRepository.save(shift));
//    }
//
//    @Transactional
//    public TimecardShiftResponse clockOut(Long shiftId) {
//        TimecardShift shift = shiftRepository.findById(shiftId)
//                .orElseThrow(() -> new RuntimeException("Shift not found"));
//
//        if (shift.getClockOutTime() != null) {
//            throw new RuntimeException("Shift already clocked out");
//        }
//
//        List<TimecardBreak> breaks = shift.getBreaks();
//        if (breaks != null) {
//            for (TimecardBreak br : breaks) {
//                if (br.getEndTime() == null) {
//                    br.setEndTime(LocalDateTime.now());
//                }
//            }
//        }
//
//        shift.setClockOutTime(LocalDateTime.now());
//        return toResponse(shiftRepository.save(shift));
//    }
//
//    @Transactional
//    public TimecardShiftResponse startBreak(Long shiftId) {
//        TimecardShift shift = shiftRepository.findById(shiftId)
//                .orElseThrow(() -> new RuntimeException("Shift not found"));
//
//        if (shift.getClockOutTime() != null) {
//            throw new RuntimeException("Shift already ended");
//        }
//
//        if (shift.getBreaks() == null) {
//            shift.setBreaks(new ArrayList<>());
//        }
//
//        boolean hasOpenBreak = shift.getBreaks()
//                .stream()
//                .anyMatch(b -> b.getEndTime() == null);
//
//        if (hasOpenBreak) {
//            throw new RuntimeException("Break already started");
//        }
//
//        TimecardBreak br = TimecardBreak.builder()
//                .shift(shift)
//                .startTime(LocalDateTime.now())
//                .build();
//
//        shift.getBreaks().add(br);
//        return toResponse(shiftRepository.save(shift));
//    }
//
//    @Transactional
//    public TimecardShiftResponse endBreak(Long shiftId) {
//        shiftRepository.findById(shiftId)
//                .orElseThrow(() -> new RuntimeException("Shift not found"));
//
//        TimecardBreak br = breakRepository.findFirstByShiftIdAndEndTimeIsNullOrderByStartTimeDesc(shiftId)
//                .orElseThrow(() -> new RuntimeException("No active break"));
//
//        br.setEndTime(LocalDateTime.now());
//        breakRepository.save(br);
//
//        TimecardShift reloaded = shiftRepository.findById(shiftId)
//                .orElseThrow(() -> new RuntimeException("Shift not found"));
//
//        return toResponse(reloaded);
//    }
//
//    private SessionInfo extractSessionInfo(String authorizationHeader) {
//        String token = authorizationHeader;
//
//        if (token != null && token.startsWith("Bearer ")) {
//            token = token.substring(7);
//        }
//
//        if (token == null || token.isBlank()) {
//            throw new RuntimeException("Authorization token is required");
//        }
//
//        String username = jwtService.extractUsername(token);
//        Long shopId = jwtService.extractShopId(token);
//        String shopCode = jwtService.extractShopCode(token);
//
//        if (username == null || username.isBlank()) {
//            throw new RuntimeException("Username not found in token");
//        }
//
//        if (shopId == null) {
//            throw new RuntimeException("Shop ID not found in token");
//        }
//
//        if (shopCode == null || shopCode.isBlank()) {
//            User user = userRepository.findByUsername(username)
//                    .orElseThrow(() -> new RuntimeException("User not found"));
//            shopCode = user.getShopCode();
//        }
//
//        return new SessionInfo(username, shopId, shopCode);
//    }
//
//    private Staff resolveStaffFromSession(SessionInfo sessionInfo) {
//        User user = userRepository.findByUsername(sessionInfo.username())
//                .orElseThrow(() -> new RuntimeException("User not found"));
//
//        Staff staff = staffRepository.findFirstByUserId(user.getId())
//                .orElseThrow(() -> new RuntimeException("Staff not found for current session user"));
//
//        if (staff.getShopId() == null || !staff.getShopId().equals(sessionInfo.shopId())) {
//            throw new RuntimeException("Staff does not belong to current session shop");
//        }
//
//        return staff;
//    }
//
//    private TimecardShiftResponse toResponse(TimecardShift shift) {
//        List<TimecardBreak> breaks = shift.getBreaks() != null
//                ? shift.getBreaks()
//                : List.of();
//
//        return TimecardShiftResponse.builder()
//                .id(shift.getId())
//                .employeeId(shift.getEmployeeId())
//                .shopId(shift.getShopId())
//                .shopCode(shift.getShopCode())
//                .workDate(shift.getWorkDate() != null ? shift.getWorkDate().toString() : null)
//                .clockInTime(shift.getClockInTime() != null ? shift.getClockInTime().toString() : null)
//                .clockOutTime(shift.getClockOutTime() != null ? shift.getClockOutTime().toString() : null)
//                .note(shift.getNote())
//                .breaks(
//                        breaks.stream()
//                                .map(br -> TimecardShiftResponse.BreakDto.builder()
//                                        .id(br.getId())
//                                        .startTime(br.getStartTime() != null ? br.getStartTime().toString() : null)
//                                        .endTime(br.getEndTime() != null ? br.getEndTime().toString() : null)
//                                        .build())
//                                .toList()
//                )
//                .build();
//    }
//
//    private record SessionInfo(String username, Long shopId, String shopCode) {
//    }
//}




package com.binhlaig.pos.timecard.service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.binhlaig.pos.auth.JwtService;
import com.binhlaig.pos.staff.entity.Staff;
import com.binhlaig.pos.staff.repository.StaffRepository;
import com.binhlaig.pos.timecard.dto.ClockInRequest;
import com.binhlaig.pos.timecard.dto.TimecardShiftResponse;
import com.binhlaig.pos.timecard.entity.TimecardBreak;
import com.binhlaig.pos.timecard.entity.TimecardShift;
import com.binhlaig.pos.timecard.repository.TimecardBreakRepository;
import com.binhlaig.pos.timecard.repository.TimecardShiftRepository;
import com.binhlaig.pos.user.User;
import com.binhlaig.pos.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class TimecardService {

    private final TimecardShiftRepository shiftRepository;
    private final TimecardBreakRepository breakRepository;
    private final StaffRepository staffRepository;
    private final UserRepository userRepository;
    private final JwtService jwtService;

    @Transactional(readOnly = true)
    public List<TimecardShiftResponse> getShifts(Long employeeId, String authorizationHeader) {
        SessionInfo sessionInfo = extractSessionInfo(authorizationHeader);
        List<TimecardShift> shifts;

        if (employeeId != null) {
            shifts = shiftRepository.findByEmployeeIdAndShopIdWithBreaksOrderByClockInTimeDesc(employeeId, sessionInfo.shopId());
        } else {
            shifts = shiftRepository.findByShopIdWithBreaksOrderByClockInTimeDesc(sessionInfo.shopId());
        }

        return shifts.stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<TimecardShiftResponse> getMyShifts(String authorizationHeader) {
        SessionInfo sessionInfo = extractSessionInfo(authorizationHeader);

        return shiftRepository.findByShopIdWithBreaksOrderByClockInTimeDesc(sessionInfo.shopId())
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public TimecardShiftResponse clockIn(ClockInRequest req, String authorizationHeader) {
        SessionInfo sessionInfo = extractSessionInfo(authorizationHeader);

        if (req.getEmployeeId() == null) {
            throw new RuntimeException("employeeId is required");
        }

        Staff staff = staffRepository.findByIdAndShopId(req.getEmployeeId(), sessionInfo.shopId())
                .orElseThrow(() -> new RuntimeException("Staff not found in current shop"));

        boolean alreadyOpen = shiftRepository.existsByEmployeeIdAndWorkDateAndClockOutTimeIsNull(
                staff.getId(),
                LocalDate.now()
        );

        if (alreadyOpen) {
            throw new RuntimeException("Employee already clocked in");
        }

        TimecardShift shift = TimecardShift.builder()
                .employeeId(staff.getId())
                .shopId(sessionInfo.shopId())
                .shopCode(sessionInfo.shopCode())
                .workDate(LocalDate.now())
                .clockInTime(LocalDateTime.now())
                .note(req.getNote())
                .build();

        if (shift.getBreaks() == null) {
            shift.setBreaks(new ArrayList<>());
        }

        return toResponse(shiftRepository.save(shift));
    }

    @Transactional
    public TimecardShiftResponse clockOut(Long shiftId, String authorizationHeader) {
        SessionInfo sessionInfo = extractSessionInfo(authorizationHeader);
        TimecardShift shift = shiftRepository.findByIdAndShopId(shiftId, sessionInfo.shopId())
                .orElseThrow(() -> new RuntimeException("Shift not found"));

        if (shift.getClockOutTime() != null) {
            throw new RuntimeException("Shift already clocked out");
        }

        List<TimecardBreak> breaks = shift.getBreaks();
        if (breaks != null) {
            for (TimecardBreak br : breaks) {
                if (br.getEndTime() == null) {
                    br.setEndTime(LocalDateTime.now());
                }
            }
        }

        shift.setClockOutTime(LocalDateTime.now());
        return toResponse(shiftRepository.save(shift));
    }

    @Transactional
    public TimecardShiftResponse startBreak(Long shiftId, String authorizationHeader) {
        SessionInfo sessionInfo = extractSessionInfo(authorizationHeader);
        TimecardShift shift = shiftRepository.findByIdAndShopId(shiftId, sessionInfo.shopId())
                .orElseThrow(() -> new RuntimeException("Shift not found"));

        if (shift.getClockOutTime() != null) {
            throw new RuntimeException("Shift already ended");
        }

        if (shift.getBreaks() == null) {
            shift.setBreaks(new ArrayList<>());
        }

        boolean hasOpenBreak = shift.getBreaks()
                .stream()
                .anyMatch(b -> b.getEndTime() == null);

        if (hasOpenBreak) {
            throw new RuntimeException("Break already started");
        }

        TimecardBreak br = TimecardBreak.builder()
                .shift(shift)
                .startTime(LocalDateTime.now())
                .build();

        shift.getBreaks().add(br);
        return toResponse(shiftRepository.save(shift));
    }

    @Transactional
    public TimecardShiftResponse endBreak(Long shiftId, String authorizationHeader) {
        SessionInfo sessionInfo = extractSessionInfo(authorizationHeader);
        shiftRepository.findByIdAndShopId(shiftId, sessionInfo.shopId())
                .orElseThrow(() -> new RuntimeException("Shift not found"));

        TimecardBreak br = breakRepository.findFirstByShiftIdAndEndTimeIsNullOrderByStartTimeDesc(shiftId)
                .orElseThrow(() -> new RuntimeException("No active break"));

        br.setEndTime(LocalDateTime.now());
        breakRepository.save(br);

        TimecardShift reloaded = shiftRepository.findByIdAndShopId(shiftId, sessionInfo.shopId())
                .orElseThrow(() -> new RuntimeException("Shift not found"));

        return toResponse(reloaded);
    }

    private SessionInfo extractSessionInfo(String authorizationHeader) {
        String token = authorizationHeader;

        if (token != null && token.startsWith("Bearer ")) {
            token = token.substring(7);
        }

        if (token == null || token.isBlank()) {
            throw new RuntimeException("Authorization token is required");
        }

        String username = jwtService.extractUsername(token);
        Long shopId = jwtService.extractShopId(token);
        String shopCode = jwtService.extractShopCode(token);

        if (username == null || username.isBlank()) {
            throw new RuntimeException("Username not found in token");
        }

        if (shopId == null) {
            throw new RuntimeException("Shop ID not found in token");
        }

        if (shopCode == null || shopCode.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Shop context missing" );
        }

        return new SessionInfo(username, shopId, shopCode);
    }

    private TimecardShiftResponse toResponse(TimecardShift shift) {
        List<TimecardBreak> breaks = shift.getBreaks() != null
                ? shift.getBreaks()
                : List.of();

        return TimecardShiftResponse.builder()
                .id(shift.getId())
                .employeeId(shift.getEmployeeId())
                .shopId(shift.getShopId())
                .shopCode(shift.getShopCode())
                .workDate(shift.getWorkDate() != null ? shift.getWorkDate().toString() : null)
                .clockInTime(shift.getClockInTime() != null ? shift.getClockInTime().toString() : null)
                .clockOutTime(shift.getClockOutTime() != null ? shift.getClockOutTime().toString() : null)
                .note(shift.getNote())
                .breaks(
                        breaks.stream()
                                .map(br -> TimecardShiftResponse.BreakDto.builder()
                                        .id(br.getId())
                                        .startTime(br.getStartTime() != null ? br.getStartTime().toString() : null)
                                        .endTime(br.getEndTime() != null ? br.getEndTime().toString() : null)
                                        .build())
                                .toList()
                )
                .build();
    }

    private record SessionInfo(String username, Long shopId, String shopCode) {
    }
}
