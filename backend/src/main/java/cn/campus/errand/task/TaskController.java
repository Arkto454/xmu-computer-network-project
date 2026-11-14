package cn.campus.errand.task;

import cn.campus.errand.common.ApiResponse;
import cn.campus.errand.student.StudentContracts.StudentProfile;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import static cn.campus.errand.task.TaskContracts.*;

@RestController
@RequestMapping("/api/student/tasks")
public class TaskController {
    private final TaskService service;
    public TaskController(TaskService service) { this.service = service; }

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
}
