

package com.binhlaig.pos.task.service;

import com.binhlaig.pos.auth.JwtService;
import com.binhlaig.pos.staff.entity.Staff;
import com.binhlaig.pos.staff.repository.StaffRepository;
import com.binhlaig.pos.task.dto.TaskRequest;
import com.binhlaig.pos.task.dto.TaskResponse;
import com.binhlaig.pos.task.entity.Task;
import com.binhlaig.pos.task.entity.TaskPriority;
import com.binhlaig.pos.task.entity.TaskStatus;
import com.binhlaig.pos.task.repository.TaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
@Transactional
public class TaskService {

    private final TaskRepository taskRepository;
    private final StaffRepository staffRepository;
    private final JwtService jwtService;

    @Transactional(readOnly = true)
    public List<TaskResponse> getAll(String token) {
        Long shopId = extractShopId(token);
        return taskRepository.findByShopIdOrderByCreatedAtDesc(shopId)
                .stream()
                .map(TaskResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<TaskResponse> getPool(String token) {
        Long shopId = extractShopId(token);
        return taskRepository.findByShopIdAndAssignedToIsNullOrderByCreatedAtDesc(shopId)
                .stream()
                .map(TaskResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<TaskResponse> getByStaff(Long staffId, String token) {
        Long shopId = extractShopId(token);
        ensureStaffInShop(staffId, shopId);

        return taskRepository.findByShopIdAndAssignedToIdOrderByCreatedAtDesc(shopId, staffId)
                .stream()
                .map(TaskResponse::from)
                .toList();
    }

    public TaskResponse create(TaskRequest request, String token) {
        Long shopId = extractShopId(token);

        Staff assignedStaff = null;
        if (request.getAssignedTo() != null) {
            assignedStaff = ensureStaffInShop(request.getAssignedTo(), shopId);
        }

        Task task = Task.builder()
                .title(required(request.getTitle(), "Task title is required"))
                .description(blankToNull(request.getDescription()))
                .dueDate(defaultDueDate(request.getDueDate()))
                .status(parseStatus(request.getStatus()))
                .priority(parsePriority(request.getPriority()))
                .subject(defaultSubject(request.getSubject()))
                .assignedTo(assignedStaff)
                .shopId(shopId)
                .build();

        Task savedTask = taskRepository.save(task);
        return TaskResponse.from(savedTask);
    }

    public TaskResponse assign(Long taskId, Long staffId, String token) {
        Long shopId = extractShopId(token);

        Task task = getTask(taskId, shopId);
        Staff staff = ensureStaffInShop(staffId, shopId);

        task.setAssignedTo(staff);
        task.setStatus(TaskStatus.Pending);

        Task updatedTask = taskRepository.save(task);
        return TaskResponse.from(updatedTask);
    }

    public TaskResponse unassign(Long taskId, String token) {
        Long shopId = extractShopId(token);

        Task task = getTask(taskId, shopId);
        task.setAssignedTo(null);

        Task updatedTask = taskRepository.save(task);
        return TaskResponse.from(updatedTask);
    }

    public TaskResponse updateStatus(Long taskId, String status, String token) {
        Long shopId = extractShopId(token);

        Task task = getTask(taskId, shopId);
        task.setStatus(parseStatus(status));

        Task updatedTask = taskRepository.save(task);
        return TaskResponse.from(updatedTask);
    }

    public void delete(Long taskId, String token) {
        Long shopId = extractShopId(token);

        Task task = getTask(taskId, shopId);
        taskRepository.delete(task);
    }

    public List<TaskResponse> getMyTasks(String token) {
        Long shopId = jwtService.extractShopId(token);
        Long staffBusinessId = jwtService.extractStaffId(token);

        if (shopId == null) {
            throw new RuntimeException("Shop ID not found in token");
        }

        if (staffBusinessId == null) {
            throw new RuntimeException("Staff ID not found in token");
        }

        Staff staff = staffRepository.findByStaffIdAndShopId(staffBusinessId, shopId)
                .orElseThrow(() -> new RuntimeException("Staff not found for current login"));

        return taskRepository
                .findByShopIdAndAssignedToIdOrderByCreatedAtDesc(shopId, staff.getId())
                .stream()
                .map(TaskResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    private Task getTask(Long taskId, Long shopId) {
        return taskRepository.findByIdAndShopId(taskId, shopId)
                .orElseThrow(() ->
                        new ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found"));
    }

    @Transactional(readOnly = true)
    private Staff ensureStaffInShop(Long staffId, Long shopId) {
        return staffRepository.findByIdAndShopId(staffId, shopId)
                .orElseThrow(() ->
                        new ResponseStatusException(HttpStatus.NOT_FOUND, "Staff not found in this shop"));
    }

    private Long extractShopId(String token) {
        try {
            Long shopId = jwtService.extractShopId(token);

            if (shopId == null) {
                throw new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED,
                        "shopId not found in token"
                );
            }

            return shopId;
        } catch (Exception e) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "Invalid or expired token"
            );
        }
    }

    private String required(String value, String message) {
        if (value == null || value.trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }
        return value.trim();
    }

    private String blankToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private String defaultDueDate(String value) {
        String v = blankToNull(value);
        return v == null ? "05:00 PM" : v;
    }

    private String defaultSubject(String value) {
        String v = blankToNull(value);
        return v == null ? "Main Branch" : v;
    }

    private TaskStatus parseStatus(String value) {
        if (value == null || value.isBlank()) {
            return TaskStatus.Pending;
        }

        String normalized = value.trim().toLowerCase(Locale.ROOT);

        return switch (normalized) {
            case "pending" -> TaskStatus.Pending;
            case "in progress", "in_progress" -> TaskStatus.In_Progress;
            case "done" -> TaskStatus.Done;
            default -> throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid task status: " + value
            );
        };
    }

    private TaskPriority parsePriority(String value) {
        if (value == null || value.isBlank()) {
            return TaskPriority.Medium;
        }

        String normalized = value.trim().toLowerCase(Locale.ROOT);

        return switch (normalized) {
            case "high" -> TaskPriority.High;
            case "medium" -> TaskPriority.Medium;
            case "low" -> TaskPriority.Low;
            default -> throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid task priority: " + value
            );
        };
    }
}
