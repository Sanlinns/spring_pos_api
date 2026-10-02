package com.binhlaig.pos.timecard.schedule;

import com.binhlaig.pos.auth.JwtService;
import com.binhlaig.pos.staff.entity.Staff;
import com.binhlaig.pos.staff.repository.StaffRepository;
import com.binhlaig.pos.timecard.schedule.dto.TimecardSchedulePublishRequest;
import com.binhlaig.pos.timecard.schedule.dto.TimecardSchedulePublishResponse;
import com.binhlaig.pos.timecard.schedule.dto.TimecardScheduleRequest;
import com.binhlaig.pos.timecard.schedule.dto.TimecardScheduleResponse;
import com.binhlaig.pos.user.User;
import com.binhlaig.pos.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
@Transactional
public class TimecardScheduleService {

    private final TimecardScheduleRepository scheduleRepository;
    private final StaffRepository staffRepository;
    private final UserRepository userRepository;
    private final JwtService jwtService;

    @Transactional(readOnly = true)
    public List<TimecardScheduleResponse> list(String from, String to, String authorizationHeader) {
        SessionInfo session = extractSessionInfo(authorizationHeader);
        LocalDate fromDate = parseDate(from, "from is required");
        LocalDate toDate = parseDate(to, "to is required");
        validateRange(fromDate, toDate);

        return scheduleRepository.findByShopIdAndDateBetweenOrderByDateAscStartTimeAsc(
                        session.shopId(),
                        fromDate,
                        toDate
                )
                .stream()
                .map(TimecardScheduleResponse::from)
                .toList();
    }

    public TimecardScheduleResponse create(TimecardScheduleRequest request, String authorizationHeader) {
        SessionInfo session = extractSessionInfo(authorizationHeader);
        ScheduleInput input = validateRequest(request);
        Staff staff = resolveStaff(input.staffId(), session.shopId());

        if (scheduleRepository.existsByShopIdAndStaffIdAndDate(session.shopId(), input.staffId(), input.date())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Schedule already exists for this staff and date");
        }

        TimecardSchedule schedule = TimecardSchedule.builder()
                .shopId(session.shopId())
                .shopCode(session.shopCode())
                .staffId(input.staffId())
                .staffName(staff.getFullName())
                .date(input.date())
                .startTime(input.startTime())
                .endTime(input.endTime())
                .role(blankToNull(request.getRole()))
                .note(blankToNull(request.getNote()))
                .status(input.status())
                .build();

        try {
            return TimecardScheduleResponse.from(scheduleRepository.save(schedule));
        } catch (DataIntegrityViolationException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Schedule already exists for this staff and date");
        }
    }

    public TimecardScheduleResponse update(Long id, TimecardScheduleRequest request, String authorizationHeader) {
        SessionInfo session = extractSessionInfo(authorizationHeader);
        ScheduleInput input = validateRequest(request);
        Staff staff = resolveStaff(input.staffId(), session.shopId());

        TimecardSchedule schedule = scheduleRepository.findByIdAndShopId(id, session.shopId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Schedule not found"));

        if (scheduleRepository.existsByShopIdAndStaffIdAndDateAndIdNot(
                session.shopId(),
                input.staffId(),
                input.date(),
                id
        )) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Schedule already exists for this staff and date");
        }

        schedule.setStaffId(input.staffId());
        schedule.setStaffName(staff.getFullName());
        schedule.setDate(input.date());
        schedule.setStartTime(input.startTime());
        schedule.setEndTime(input.endTime());
        schedule.setRole(blankToNull(request.getRole()));
        schedule.setNote(blankToNull(request.getNote()));
        schedule.setStatus(input.status());

        try {
            return TimecardScheduleResponse.from(scheduleRepository.save(schedule));
        } catch (DataIntegrityViolationException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Schedule already exists for this staff and date");
        }
    }

    public void delete(Long id, String authorizationHeader) {
        SessionInfo session = extractSessionInfo(authorizationHeader);
        TimecardSchedule schedule = scheduleRepository.findByIdAndShopId(id, session.shopId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Schedule not found"));
        scheduleRepository.delete(schedule);
    }

    public TimecardSchedulePublishResponse publish(TimecardSchedulePublishRequest request, String authorizationHeader) {
        SessionInfo session = extractSessionInfo(authorizationHeader);
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Publish range is required");
        }

        LocalDate from = parseDate(request.getFrom(), "from is required");
        LocalDate to = parseDate(request.getTo(), "to is required");
        validateRange(from, to);

        int count = scheduleRepository.publishDrafts(session.shopId(), from, to);
        return TimecardSchedulePublishResponse.builder()
                .publishedCount(count)
                .from(from.toString())
                .to(to.toString())
                .build();
    }

    private ScheduleInput validateRequest(TimecardScheduleRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Schedule request is required");
        }

        String staffId = required(request.getStaffId(), "staffId is required");
        LocalDate date = parseDate(request.getDate(), "date is required");
        LocalTime startTime = parseTime(request.getStartTime(), "startTime is required");
        LocalTime endTime = parseTime(request.getEndTime(), "endTime is required");

        if (!endTime.isAfter(startTime)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "endTime must be after startTime");
        }

        return new ScheduleInput(staffId, date, startTime, endTime, parseStatus(request.getStatus()));
    }

    private Staff resolveStaff(String staffId, Long shopId) {
        Long numericStaffId = parseStaffBusinessId(staffId);
        if (numericStaffId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "staffId must contain a numeric staff id");
        }

        return staffRepository.findByStaffIdAndShopId(numericStaffId, shopId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Staff not found in current shop"));
    }

    private Long parseStaffBusinessId(String staffId) {
        String digits = staffId.replaceAll("\\D", "");
        if (digits.isBlank()) {
            return null;
        }
        return Long.valueOf(digits);
    }

    private SessionInfo extractSessionInfo(String authorizationHeader) {
        String token = authorizationHeader;
        if (token != null && token.startsWith("Bearer ")) {
            token = token.substring(7).trim();
        }

        if (token == null || token.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authorization token is required");
        }

        String username = jwtService.extractUsername(token);
        Long shopId = jwtService.extractShopId(token);
        String shopCode = jwtService.extractShopCode(token);

        if (username == null || username.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Username not found in token");
        }

        if (shopId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Shop ID not found in token");
        }

        if (shopCode == null || shopCode.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Shop context missing" );
        }

        if (shopCode == null || shopCode.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Shop code not found in token");
        }

        return new SessionInfo(shopId, shopCode.trim());
    }

    private TimecardScheduleStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return TimecardScheduleStatus.DRAFT;
        }

        try {
            return TimecardScheduleStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid schedule status: " + status);
        }
    }

    private LocalDate parseDate(String value, String message) {
        String clean = required(value, message);
        try {
            return LocalDate.parse(clean);
        } catch (DateTimeParseException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid date: " + value);
        }
    }

    private LocalTime parseTime(String value, String message) {
        String clean = required(value, message);
        try {
            return LocalTime.parse(clean);
        } catch (DateTimeParseException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid time: " + value);
        }
    }

    private void validateRange(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "to must be on or after from");
        }
    }

    private String required(String value, String message) {
        if (value == null || value.trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }
        return value.trim();
    }

    private String blankToNull(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim();
    }

    private record SessionInfo(Long shopId, String shopCode) {
    }

    private record ScheduleInput(
            String staffId,
            LocalDate date,
            LocalTime startTime,
            LocalTime endTime,
            TimecardScheduleStatus status
    ) {
    }
}
