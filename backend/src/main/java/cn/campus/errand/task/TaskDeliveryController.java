package cn.campus.errand.task;

import cn.campus.errand.common.ApiResponse;
import cn.campus.errand.student.StudentContracts.StudentProfile;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import static cn.campus.errand.task.TaskDeliveryContracts.*;

@RestController
@RequestMapping("/api/student")
public class TaskDeliveryController {
    private static final String DELIVERY = "/tasks/{id}/delivery";
    private final TaskDeliveryService service;

    public TaskDeliveryController(TaskDeliveryService service) {
        this.service = service;
    }

    @PostMapping(DELIVERY)
    public ResponseEntity<ApiResponse<CodeView>> requestDelivery(@RequestAttribute("currentStudent") StudentProfile student,
                                                                  @PathVariable long id) {
        return codeResponse(service.requestDelivery(student.id(), id));
    }

    @GetMapping(DELIVERY + "/code")
    public ResponseEntity<ApiResponse<CodeView>> currentCode(@RequestAttribute("currentStudent") StudentProfile student,
                                                             @PathVariable long id) {
        return codeResponse(service.currentCode(student.id(), id));
    }

    @PostMapping(DELIVERY + "/code/refresh")
    public ResponseEntity<ApiResponse<CodeView>> refresh(@RequestAttribute("currentStudent") StudentProfile student,
                                                         @PathVariable long id, @Valid @RequestBody CodeVersion input) {
        return codeResponse(service.refresh(student.id(), id, input.codeVersion()));
    }

    @PostMapping(DELIVERY + "/code/revoke")
    public ResponseEntity<ApiResponse<CodeView>> revoke(@RequestAttribute("currentStudent") StudentProfile student,
                                                        @PathVariable long id, @Valid @RequestBody CodeVersion input) {
        return codeResponse(service.revoke(student.id(), id, input.codeVersion()));
    }

    @PostMapping(DELIVERY + "/verify")
    public ApiResponse<Verification> verify(@RequestAttribute("currentStudent") StudentProfile student,
                                            @PathVariable long id, @Valid @RequestBody ScannedCode input) {
        return ApiResponse.success(service.verify(student.id(), id, input.payload()));
    }

    @PostMapping(DELIVERY + "/confirm")
    public ResponseEntity<ApiResponse<Completion>> confirm(@RequestAttribute("currentStudent") StudentProfile student,
                                            @PathVariable long id, @Valid @RequestBody ScannedCode input) {
        Completion result = service.confirm(student.id(), id, input.payload());
        return ResponseEntity.status(result.outcome() == ConfirmationOutcome.PENDING ? 202 : 200)
                .body(ApiResponse.success(result));
    }

    @GetMapping(DELIVERY + "/confirmation")
    public ApiResponse<Completion> confirmation(@RequestAttribute("currentStudent") StudentProfile student,
                                                @PathVariable long id) {
        return ApiResponse.success(service.confirmation(student.id(), id));
    }

    @GetMapping("/earnings")
    public ApiResponse<Earnings> earnings(@RequestAttribute("currentStudent") StudentProfile student,
                                          @RequestParam(defaultValue = "1") int page,
                                          @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(service.earnings(student.id(), page, size));
    }

    private static ResponseEntity<ApiResponse<CodeView>> codeResponse(CodeView code) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(code));
    }
}
