package cn.campus.errand.task;

import cn.campus.errand.admin.AuthContracts.AdminProfile;
import cn.campus.errand.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import static cn.campus.errand.task.AdminTaskContracts.*;

@RestController
@RequestMapping("/api/admin/tasks")
public class AdminTaskController {
    private final AdminTaskService service;

    public AdminTaskController(AdminTaskService service) { this.service = service; }

    @GetMapping
    public ApiResponse<Page> list(@RequestParam(required = false) Long taskId,
                                  @RequestParam(required = false) TaskStatus status,
                                  @RequestParam(defaultValue = "1") int page,
                                  @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(service.list(taskId, status, page, size));
    }

    @GetMapping("/{id}")
    public ApiResponse<Detail> detail(@PathVariable long id) {
        return ApiResponse.success(service.detail(id));
    }

    @PostMapping("/{id}/close")
    public ApiResponse<Detail> close(@RequestAttribute("currentAdmin") AdminProfile admin,
                                     @PathVariable long id, @Valid @RequestBody CloseRequest input) {
        return ApiResponse.success(service.close(admin.id(), id, input));
    }
}
