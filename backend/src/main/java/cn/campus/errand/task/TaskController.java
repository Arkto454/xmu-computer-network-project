package cn.campus.errand.task;

import cn.campus.errand.common.ApiResponse;
import cn.campus.errand.student.StudentContracts.StudentProfile;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import static cn.campus.errand.task.TaskContracts.*;

@RestController
@RequestMapping("/api/student/tasks")
public class TaskController {
    private final TaskService service;
    private final TaskClaimService claims;
    private final TaskCancellationService cancellations;

    public TaskController(TaskService service, TaskClaimService claims, TaskCancellationService cancellations) {
        this.service = service;
        this.claims = claims;
        this.cancellations = cancellations;
    }

    @PostMapping
    public ApiResponse<Created> publish(@RequestAttribute("currentStudent") StudentProfile student,
                                       @Valid @RequestBody CreateRequest request) {
        return ApiResponse.success(service.publish(student.id(), request));
    }

    @GetMapping
    public ApiResponse<Page> list(@RequestParam(defaultValue = "1") int page,
                                  @RequestParam(defaultValue = "20") int size,
                                  @RequestParam(required = false) TaskType type) {
        return ApiResponse.success(service.list(page, size, type));
    }

    @GetMapping("/{id}")
    public ApiResponse<Detail> detail(@RequestAttribute("currentStudent") StudentProfile student,
                                     @PathVariable long id) {
        return ApiResponse.success(service.detail(student.id(), id));
    }

    @GetMapping("/mine")
    public ApiResponse<Page> mine(@RequestAttribute("currentStudent") StudentProfile student,
                                  @RequestParam(defaultValue = "1") int page,
                                  @RequestParam(defaultValue = "20") int size,
                                  @RequestParam(required = false) TaskStatus status) {
        return ApiResponse.success(service.listMine(student.id(), page, size, status));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<StateResult> cancel(@RequestAttribute("currentStudent") StudentProfile student,
                                           @PathVariable long id) {
        return ApiResponse.success(service.cancel(student.id(), id));
    }

    @PostMapping("/{id}/claim")
    public ResponseEntity<ApiResponse<ClaimResult>> claim(
            @RequestAttribute("currentStudent") StudentProfile student, @PathVariable long id,
            @Valid @RequestBody ClaimRequest input) {
        ClaimResult result = claims.submit(student.id(), id, input);
        ClaimOutcome outcome = result.outcome();
        return switch (outcome) {
            case ACCEPTED, ALREADY_ACCEPTED -> ResponseEntity.ok(ApiResponse.success(result));
            case PENDING -> ResponseEntity.accepted().body(new ApiResponse<>("CLAIM_PENDING", outcome.message(), result));
            default -> ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ApiResponse<>(outcome.name(), outcome.message(), result));
        };
    }

    @GetMapping("/accepted")
    public ApiResponse<Page> accepted(@RequestAttribute("currentStudent") StudentProfile student,
                                     @RequestParam(defaultValue = "1") int page,
                                     @RequestParam(defaultValue = "20") int size,
                                     @RequestParam(required = false) TaskStatus status) {
        return ApiResponse.success(service.listAccepted(student.id(), page, size, status));
    }

    @PostMapping("/{id}/pickup")
    public ApiResponse<TaskCancellationContracts.PickupResult> pickup(
            @RequestAttribute("currentStudent") StudentProfile student, @PathVariable long id) {
        return ApiResponse.success(cancellations.confirmPickup(student.id(), id));
    }

    @PostMapping("/{id}/cancellations")
    public ApiResponse<TaskCancellationContracts.Detail> requestCancellation(
            @RequestAttribute("currentStudent") StudentProfile student, @PathVariable long id,
            @Valid @RequestBody TaskCancellationContracts.CreateRequest input) {
        return ApiResponse.success(cancellations.create(student.id(), id, input));
    }

    @GetMapping("/{id}/cancellations/latest")
    public ApiResponse<TaskCancellationContracts.Detail> latestCancellation(
            @RequestAttribute("currentStudent") StudentProfile student, @PathVariable long id) {
        return ApiResponse.success(cancellations.latest(student.id(), id));
    }

    @PostMapping("/{id}/cancellations/{cancellationId}/actions")
    public ApiResponse<TaskCancellationContracts.Detail> cancellationAction(
            @RequestAttribute("currentStudent") StudentProfile student, @PathVariable long id,
            @PathVariable long cancellationId, @Valid @RequestBody TaskCancellationContracts.StudentDecision input) {
        return ApiResponse.success(cancellations.studentAction(student.id(), id, cancellationId, input.action()));
    }
}
