package cn.campus.errand.task;

import cn.campus.errand.admin.AdminTokenStore;
import cn.campus.errand.student.StudentAuthService;
import cn.campus.errand.student.StudentContracts;
import cn.campus.errand.student.WeChatGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 微信网关隔离；身份拦截、业务代码和数据库操作均真实执行。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TaskIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-11-14T04:00:00Z");
    private static final String PHONE = "13800000000";
    private static final String WECHAT = "demo_wechat_contact";
    private static final String PRIVATE = "TEST-ONLY-PICKUP-CODE";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired TaskMapper mapper;
    @Autowired StudentAuthService studentAuth;
    @Autowired AdminTokenStore adminTokens;
    @MockitoBean WeChatGateway wechat;
    @MockitoBean(name = "taskClock") Clock clock;
    StudentContracts.LoginResult publisher;
    StudentContracts.LoginResult viewer;

    @BeforeEach
    void prepare() {
        jdbc.update("DELETE FROM errand_task");
        jdbc.update("DELETE FROM student_user");
        when(clock.instant()).thenReturn(NOW);
        when(wechat.exchange("publisher-code")).thenReturn(new WeChatGateway.Identity("task-test-app", "publisher"));
        when(wechat.exchange("viewer-code")).thenReturn(new WeChatGateway.Identity("task-test-app", "viewer"));
        publisher = studentAuth.login(new StudentContracts.LoginRequest("publisher-code"));
        viewer = studentAuth.login(new StudentContracts.LoginRequest("viewer-code"));
    }

    private Map<String, Object> validBody() {
        var body = new LinkedHashMap<String, Object>();
        body.put("title", "  帮取快递  ");
        body.put("type", "EXPRESS");
        body.put("pickupLocation", "校园快递站东门");
        body.put("deliveryLocation", "宿舍楼一楼门口");
        body.put("deadlineAt", NOW.plusSeconds(3600).toString());
        body.put("rewardAmount", new BigDecimal("3.50"));
        body.put("description", "两个小包裹");
        body.put("publisherPhone", PHONE);
        body.put("publisherWechat", WECHAT);
        body.put("privateInstructions", PRIVATE);
        return body;
    }

    private String bearer(StudentContracts.LoginResult login) { return "Bearer " + login.accessToken(); }

    private long publish(Map<String, Object> body) throws Exception {
        String result = mvc.perform(post("/api/student/tasks").header("Authorization", bearer(publisher))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("WAITING"))
                .andExpect(content().string(not(containsString(PHONE))))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(result).path("data").path("id").asLong();
    }

    @Test
    void publishingUsesAuthenticatedIdentityAndPersistsOriginalValues() throws Exception {
        var body = validBody();
        body.put("publisherId", viewer.student().id());
        body.put("status", "COMPLETED");
        long id = publish(body);
        var row = mapper.findById(id);
        assertThat(row.publisherId).isEqualTo(publisher.student().id());
        assertThat(row.title).isEqualTo("帮取快递");
        assertThat(row.status).isEqualTo(TaskStatus.WAITING);
        assertThat(row.rewardAmount).isEqualByComparingTo("3.50");
        assertThat(row.publisherPhone).isEqualTo(PHONE);
        assertThat(row.privateInstructions).isEqualTo(PRIVATE);
    }

    @Test
    void publisherCanReadOwnPrivateDetailsButOtherStudentCannot() throws Exception {
        long id = publish(validBody());
        mvc.perform(get("/api/student/tasks/{id}", id).header("Authorization", bearer(publisher)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.ownedByMe").value(true))
                .andExpect(jsonPath("$.data.privateInfo.publisherPhone").value(PHONE))
                .andExpect(jsonPath("$.data.privateInfo.publisherWechat").value(WECHAT))
                .andExpect(jsonPath("$.data.privateInfo.privateInstructions").value(PRIVATE))
                .andExpect(jsonPath("$.data.title").value("帮取快递"))
                .andExpect(jsonPath("$.data.deadlineAt").value(NOW.plusSeconds(3600).toString()));
        mvc.perform(get("/api/student/tasks/{id}", id).param("publisherId", publisher.student().id() + "")
                        .param("ownedByMe", "true").header("Authorization", bearer(viewer)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.ownedByMe").value(false))
                .andExpect(jsonPath("$.data.description").value("两个小包裹"))
                .andExpect(jsonPath("$.data.privateInfo").doesNotExist())
                .andExpect(content().string(not(containsString(PHONE))))
                .andExpect(content().string(not(containsString(WECHAT))))
                .andExpect(content().string(not(containsString(PRIVATE))));
    }

    @Test
    void listNeverReturnsPrivateInformationEvenToPublisher() throws Exception {
        long id = publish(validBody());
        for (var login : new StudentContracts.LoginResult[]{publisher, viewer}) {
            mvc.perform(get("/api/student/tasks").header("Authorization", bearer(login)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
                    .andExpect(jsonPath("$.data.items[0].id").value(id))
                    .andExpect(jsonPath("$.data.items[0].publisherPhone").doesNotExist())
                    .andExpect(content().string(not(containsString(PHONE))))
                    .andExpect(content().string(not(containsString(WECHAT))))
                    .andExpect(content().string(not(containsString(PRIVATE))));
        }
    }

    @Test
    void paginationHasStableNewestFirstOrderingIncludingTies() throws Exception {
        long first = publish(validBody());
        when(clock.instant()).thenReturn(NOW.plusSeconds(10));
        long second = publish(validBody());
        long third = publish(validBody());
        mvc.perform(get("/api/student/tasks").param("page", "1").param("size", "2").header("Authorization", bearer(viewer)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].id").value(third))
                .andExpect(jsonPath("$.data.items[1].id").value(second));
        mvc.perform(get("/api/student/tasks").param("page", "2").param("size", "2").header("Authorization", bearer(viewer)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(first));
    }

    @Test
    void filtersByTypeAndReturnsEmptyPagePastLastItemWithoutOffsetOverflow() throws Exception {
        publish(validBody());
        var food = validBody();
        food.put("type", "FOOD");
        long foodId = publish(food);
        mvc.perform(get("/api/student/tasks").param("type", "FOOD").header("Authorization", bearer(viewer)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(foodId));
        mvc.perform(get("/api/student/tasks").param("page", "2147483647").param("size", "100")
                        .header("Authorization", bearer(viewer)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(0))
                .andExpect(jsonPath("$.data.total").value(2));
    }

    static Stream<Arguments> invalidFields() {
        return Stream.of(
                Arguments.of("title", " "), Arguments.of("title", "a".repeat(101)),
                Arguments.of("type", "UNKNOWN"), Arguments.of("type", null),
                Arguments.of("pickupLocation", " "), Arguments.of("pickupLocation", "a".repeat(201)),
                Arguments.of("deliveryLocation", null), Arguments.of("deliveryLocation", "a".repeat(201)),
                Arguments.of("publisherPhone", "abc"), Arguments.of("publisherPhone", "123"),
                Arguments.of("publisherPhone", null), Arguments.of("publisherPhone", "1".repeat(16)),
                Arguments.of("rewardAmount", null), Arguments.of("rewardAmount", BigDecimal.ZERO),
                Arguments.of("rewardAmount", new BigDecimal("-1")), Arguments.of("rewardAmount", new BigDecimal("1.001")),
                Arguments.of("rewardAmount", new BigDecimal("100000000")),
                Arguments.of("deadlineAt", null), Arguments.of("deadlineAt", "not-a-time"),
                Arguments.of("deadlineAt", "+10000-01-01T00:00:00Z"),
                Arguments.of("description", "a".repeat(1001)), Arguments.of("publisherWechat", "a".repeat(65)),
                Arguments.of("privateInstructions", "a".repeat(1001)));
    }

    @ParameterizedTest(name = "invalid {0}, case {index}")
    @MethodSource("invalidFields")
    void rejectsInvalidInputWithoutCreatingTask(String field, Object value) throws Exception {
        var body = validBody();
        body.put(field, value);
        mvc.perform(post("/api/student/tasks").header("Authorization", bearer(publisher))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM errand_task", Integer.class)).isZero();
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, 0})
    void deadlineMustBeStrictlyInTheFuture(long delta) throws Exception {
        var body = validBody();
        body.put("deadlineAt", NOW.plusSeconds(delta).toString());
        mvc.perform(post("/api/student/tasks").header("Authorization", bearer(publisher))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void optionalFieldsCanBeOmittedAndInternationalPhonePrefixIsAccepted() throws Exception {
        var body = validBody();
        body.remove("publisherWechat");
        body.remove("privateInstructions");
        body.remove("description");
        body.put("publisherPhone", "+8613800000000");
        var row = mapper.findById(publish(body));
        assertThat(row.publisherWechat).isNull();
        assertThat(row.privateInstructions).isNull();
        assertThat(row.description).isNull();
    }

    @Test
    void deadlineCannotBecomeExpiredThroughDatabasePrecisionLoss() throws Exception {
        var body = validBody();
        body.put("deadlineAt", NOW.plusNanos(1).toString());
        mvc.perform(post("/api/student/tasks").header("Authorization", bearer(publisher))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body)))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM errand_task", Integer.class)).isZero();
    }

    @Test
    void expirationIsFilteredAndPresentedWithoutChangingStoredState() throws Exception {
        long id = publish(validBody());
        when(clock.instant()).thenReturn(NOW.plusSeconds(3600));
        mvc.perform(get("/api/student/tasks").header("Authorization", bearer(viewer)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        mvc.perform(get("/api/student/tasks/{id}", id).header("Authorization", bearer(publisher)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("EXPIRED"))
                .andExpect(jsonPath("$.data.privateInfo").doesNotExist());
        mvc.perform(get("/api/student/tasks/{id}", id).header("Authorization", bearer(viewer)))
                .andExpect(status().isNotFound());
        assertThat(mapper.findById(id).status).isEqualTo(TaskStatus.WAITING);
    }

    @ParameterizedTest
    @EnumSource(value = TaskStatus.class, names = "WAITING", mode = EnumSource.Mode.EXCLUDE)
    void nonWaitingTasksAreNotInHall(TaskStatus status) throws Exception {
        long id = publish(validBody());
        jdbc.update("UPDATE errand_task SET status=? WHERE id=?", status.name(), id);
        mvc.perform(get("/api/student/tasks").header("Authorization", bearer(viewer)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"COMPLETED", "CANCELLED", "EXPIRED", "CLOSED"})
    void endedTasksDoNotExposePrivateInfoAndAreHiddenFromUnrelatedUsers(String state) throws Exception {
        long id = publish(validBody());
        jdbc.update("UPDATE errand_task SET status=? WHERE id=?", state, id);
        mvc.perform(get("/api/student/tasks/{id}", id).header("Authorization", bearer(publisher)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.privateInfo").doesNotExist());
        mvc.perform(get("/api/student/tasks/{id}", id).header("Authorization", bearer(viewer)))
                .andExpect(status().isNotFound());
    }

    @Test
    void allTaskEndpointsRequireStudentIdentity() throws Exception {
        mvc.perform(post("/api/student/tasks").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(validBody())))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/student/tasks")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/student/tasks/1")).andExpect(status().isUnauthorized());
        String adminToken = adminTokens.issue(publisher.student().id()).value();
        mvc.perform(get("/api/student/tasks").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void disabledStudentCannotPublishOrReadTaskData() throws Exception {
        long id = publish(validBody());
        jdbc.update("UPDATE student_user SET enabled=FALSE WHERE id=?", publisher.student().id());
        mvc.perform(post("/api/student/tasks").header("Authorization", bearer(publisher))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(validBody())))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/student/tasks/{id}", id).header("Authorization", bearer(publisher)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void invalidQueryParametersAndTaskIdsAreClientErrors() throws Exception {
        for (String[] query : new String[][]{{"page","0"},{"page","abc"},{"size","0"},{"size","101"},{"type","UNKNOWN"}}) {
            mvc.perform(get("/api/student/tasks").param(query[0],query[1]).header("Authorization", bearer(viewer)))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        mvc.perform(get("/api/student/tasks/9223372036854775807").header("Authorization", bearer(viewer)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("TASK_NOT_FOUND"));
        mvc.perform(get("/api/student/tasks/-1").header("Authorization", bearer(viewer))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/student/tasks/not-a-number").header("Authorization", bearer(viewer))).andExpect(status().isBadRequest());
    }

    @Test
    void changingPersonalContactDoesNotChangeTaskSnapshot() throws Exception {
        long id = publish(validBody());
        jdbc.update("UPDATE student_user SET contact=? WHERE id=?", "13900000000", publisher.student().id());
        mvc.perform(get("/api/student/tasks/{id}", id).header("Authorization", bearer(publisher)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.privateInfo.publisherPhone").value(PHONE));
    }
}
