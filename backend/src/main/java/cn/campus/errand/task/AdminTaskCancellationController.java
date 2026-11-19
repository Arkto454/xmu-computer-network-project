package cn.campus.errand.task;

import cn.campus.errand.admin.AuthContracts.AdminProfile;
import cn.campus.errand.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import static cn.campus.errand.task.TaskCancellationContracts.*;

@RestController
@RequestMapping("/api/admin/task-cancellations")
public class AdminTaskCancellationController {
    private final TaskCancellationService cancellations;

    public AdminTaskCancellationController(TaskCancellationService cancellations) {
        this.cancellations = cancellations;
    }

    @GetMapping
    public ApiResponse<Page> list(@RequestParam(defaultValue = "AWAITING_REVIEW") Status status,
                                  @RequestParam(defaultValue = "1") int page,
                                  @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(cancellations.adminList(status, page, size));
    }

    @PostMapping("/{cancellationId}/decision")
    public ApiResponse<Detail> review(@RequestAttribute("currentAdmin") AdminProfile admin,
                                      @PathVariable long cancellationId, @Valid @RequestBody AdminDecision input) {
        return ApiResponse.success(cancellations.review(admin.id(), cancellationId, input));
    }
}
