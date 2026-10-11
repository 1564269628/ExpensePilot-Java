package com.expensepilot.api;

import com.expensepilot.security.CurrentUser;
import com.expensepilot.security.TaskAuthorizationService;
import com.expensepilot.service.ExpenseTaskQueryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Real HTTP API for the local single-user workspace.
 *
 * Receipts and trips here are USER-ENTERED DATA, not imaginary results from a
 * real mailbox, cloud drive, corporate booking service, or enterprise policy.
 * The Planner/ChatClient still makes real calls to the configured Groq model.
 */
@RestController
@RequestMapping("/api/v1/local")
@Profile("local")
public class LocalWorkspaceController {

    private static final Set<String> SOURCES = Set.of("EMAIL", "DRIVE", "UPLOAD");
    private static final Set<String> CATEGORIES =
            Set.of("HOTEL", "TRANSPORT", "MEAL", "OTHER");

    private final JdbcTemplate jdbc;
    private final CurrentUser currentUser;
    private final TaskAuthorizationService taskAuthorization;
    private final ExpenseTaskQueryService taskQuery;
    private final ChatClient groq;
    private final Path uploads;

    public LocalWorkspaceController(
            JdbcTemplate jdbc,
            CurrentUser currentUser,
            TaskAuthorizationService taskAuthorization,
            ExpenseTaskQueryService taskQuery,
            ChatClient.Builder groqBuilder,
            @Value("${expensepilot.local.upload-dir:/app/data/uploads}") String uploadDir) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
        this.taskAuthorization = taskAuthorization;
        this.taskQuery = taskQuery;
        this.groq = groqBuilder.build();
        this.uploads = Path.of(uploadDir).toAbsolutePath().normalize();
    }

    @GetMapping("/overview")
    public Map<String, Object> overview() {
        String user = currentUser.userId();
        List<Map<String, Object>> byStatus = jdbc.queryForList("""
                select status, count(*) as total from expense_task
                 where user_id=? group by status
                """, user);
        return Map.of(
                "userId", user,
                "model", "qwen/qwen3.8-27b",
                "provider", "Groq",
                "statusCounts", byStatus,
                "documents", count("local_document", user),
                "trips", count("local_trip", user),
                "submissions", count("local_expense_submission", user),
                "notifications", count("local_notification", user),
                "workspaceMode", "LOCAL_DATA_NOT_ENTERPRISE_CONNECTORS");
    }

    private int count(String table, String user) {
        // Table is chosen exclusively from internal constant strings.
        return jdbc.queryForObject(
                "select count(*) from " + table + " where user_id=?",
                Integer.class, user);
    }

    @GetMapping("/tasks")
    public List<Map<String, Object>> tasks() {
        return jdbc.queryForList("""
                select id as taskId, status, current_node as currentNode,
                       retry_count as retryCount, last_error as lastError,
                       created_at as createdAt, updated_at as updatedAt
                  from expense_task where user_id=?
                 order by created_at desc limit 100
                """, currentUser.userId());
    }

    @GetMapping("/tasks/{taskId}/details")
    public Map<String, Object> details(@PathVariable long taskId) {
        taskAuthorization.assertOwner(taskId, currentUser.userId());
        return Map.of(
                "task", taskQuery.get(taskId),
                "steps", jdbc.queryForList("""
                        select step_key as stepKey,step_type as stepType,status,
                               retry_count as retryCount,error_message as errorMessage
                          from agent_step where task_id=? order by id
                        """, taskId),
                "approvals", jdbc.queryForList("""
                        select operation_type as operationType,status,approver,
                               comment_text as comment
                          from approval_record where task_id=? order by created_at
                        """, taskId),
                "toolExecutions", jdbc.queryForList("""
                        select tool_name as toolName,operation_type as operationType,
                               status,external_business_no as businessNo
                          from tool_execution_record
                         where task_id=? order by id
                        """, taskId));
    }

    @GetMapping("/documents")
    public List<Map<String, Object>> documents() {
        return jdbc.queryForList("""
                select document_ref as documentRef,source_type as sourceType,
                       file_name as fileName,category,amount,city,issued_on as issuedOn,
                       is_sample as sample,created_at as createdAt
                  from local_document where user_id=?
                 order by id desc limit 200
                """, currentUser.userId());
    }

    @PostMapping("/documents")
    public Map<String, Object> createDocument(@Valid @RequestBody DocumentInput input) {
        validateDocument(input.sourceType(), input.category(), input.amount());
        String reference = "local://document/" + UUID.randomUUID();
        jdbc.update("""
                insert into local_document(
                    document_ref,user_id,source_type,file_name,
                    category,amount,city,issued_on,is_sample
                ) values (?,?,?,?,?,?,?,?,false)
                """, reference, currentUser.userId(), input.sourceType(),
                input.fileName(), input.category(), input.amount(),
                input.city(), java.sql.Date.valueOf(input.issuedOn()));
        return Map.of("documentRef", reference, "message", "Material saved");
    }

    @PostMapping(value = "/documents/upload",
                 consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam String city,
            @RequestParam String category,
            @RequestParam BigDecimal amount,
            @RequestParam LocalDate issuedOn) throws java.io.IOException {
        validateDocument("UPLOAD", category, amount);
        if (city.isBlank()) throw new IllegalArgumentException("City required");
        String name = file.getOriginalFilename() == null
                ? "document" : Path.of(file.getOriginalFilename()).getFileName().toString();
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        if (!(lower.endsWith(".pdf") || lower.endsWith(".png")
                || lower.endsWith(".jpg") || lower.endsWith(".jpeg"))) {
            throw new IllegalArgumentException("Only PDF/JPG/PNG files supported");
        }
        if (file.isEmpty() || file.getSize() > 8L * 1024L * 1024L) {
            throw new IllegalArgumentException("File must be between 1 byte and 8 MB");
        }
        Files.createDirectories(uploads);
        String fileId = UUID.randomUUID().toString();
        Path saved = uploads.resolve(fileId + lower.substring(lower.lastIndexOf('.')));
        Files.copy(file.getInputStream(), saved);
        String reference = "local://document/" + fileId;
        try {
            jdbc.update("""
                    insert into local_document(
                        document_ref,user_id,source_type,file_name,file_path,
                        category,amount,city,issued_on,is_sample
                    ) values (?,?,?,?,?,?,?,?,?,false)
                    """, reference, currentUser.userId(), "UPLOAD",
                    name, saved.toString(), category, amount,
                    city, java.sql.Date.valueOf(issuedOn));
        }
        catch (RuntimeException error) {
            Files.deleteIfExists(saved);
            throw error;
        }
        return Map.of("documentRef", reference,
                "message", "File stored; entered metadata will be used for matching");
    }

    @GetMapping("/documents/file")
    public ResponseEntity<FileSystemResource> download(@RequestParam String ref) {
        var matches = jdbc.query("""
                select file_name,file_path from local_document
                 where user_id=? and document_ref=?
                """, (rs, i) -> Map.of(
                        "fileName", rs.getString("file_name"),
                        "filePath", rs.getString("file_path") == null
                                ? "" : rs.getString("file_path")),
                currentUser.userId(), ref);
        if (matches.isEmpty() || matches.get(0).get("filePath").isBlank()) {
            return ResponseEntity.notFound().build();
        }
        Path candidate = Path.of(matches.get(0).get("filePath")).normalize();
        if (!candidate.startsWith(uploads) || !Files.isRegularFile(candidate)) {
            return ResponseEntity.notFound().build();
        }
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(matches.get(0).get("fileName"), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new FileSystemResource(candidate));
    }

    @GetMapping("/trips")
    public List<Map<String, Object>> trips() {
        return jdbc.queryForList("""
                select id,city,start_date as startDate,end_date as endDate,
                       purpose,is_sample as sample,created_at as createdAt
                  from local_trip where user_id=? order by id desc limit 200
                """, currentUser.userId());
    }

    @PostMapping("/trips")
    public Map<String, Object> createTrip(@Valid @RequestBody TripInput input) {
        if (input.startDate().isAfter(input.endDate())) {
            throw new IllegalArgumentException("Start date must not be after end date");
        }
        jdbc.update("""
                insert into local_trip(
                    user_id,city,start_date,end_date,purpose,is_sample
                ) values (?,?,?,?,?,false)
                """, currentUser.userId(), input.city(),
                java.sql.Date.valueOf(input.startDate()),
                java.sql.Date.valueOf(input.endDate()), input.purpose());
        return Map.of("message", "Trip recorded in local workspace");
    }

    @GetMapping("/policies")
    public List<Map<String, Object>> policies() {
        return jdbc.queryForList("""
                select id,city,category,max_amount as maxAmount,description
                  from local_policy_rule order by city,category
                """);
    }

    @PostMapping("/policies")
    public Map<String, Object> updatePolicy(@Valid @RequestBody PolicyInput input) {
        if (!CATEGORIES.contains(input.category())) {
            throw new IllegalArgumentException("Unknown category");
        }
        jdbc.update("""
                insert into local_policy_rule(city,category,max_amount,description)
                values (?,?,?,?)
                on duplicate key update max_amount=values(max_amount),
                    description=values(description)
                """, input.city(), input.category(),
                input.maxAmount(), input.description());
        return Map.of("message", "Local demo policy updated");
    }

    @GetMapping("/submissions")
    public List<Map<String, Object>> submissions() {
        return jdbc.queryForList("""
                select business_no as businessNo, created_at as submittedAt,
                       draft_json as draft
                  from local_expense_submission
                 where user_id=? order by created_at desc limit 100
                """, currentUser.userId());
    }

    @GetMapping("/notifications")
    public List<Map<String, Object>> notifications() {
        return jdbc.queryForList("""
                select business_no as businessNo,event_id as eventId,
                       created_at as sentAt
                  from local_notification
                 where user_id=? order by created_at desc limit 100
                """, currentUser.userId());
    }

    @PostMapping("/groq-check")
    public Map<String, Object> groqCheck() {
        String answer = groq.prompt()
                .system("Answer in one short Chinese sentence. No reasoning.")
                .user("用一句话介绍企业报销审批的作用。")
                .call().content();
        return Map.of(
                "provider", "Groq",
                "model", "qwen/qwen3.8-27b",
                "response", answer == null ? "" : answer,
                "live", true);
    }

    @PostMapping("/demo-seed")
    @Transactional
    public Map<String, Object> demoSeed() {
        String user = currentUser.userId();
        Integer existing = jdbc.queryForObject("""
                select count(*) from local_trip where user_id=? and is_sample=true
                """, Integer.class, user);
        if (existing == null || existing == 0) {
            jdbc.update("""
                    insert into local_trip(
                        user_id,city,start_date,end_date,purpose,is_sample
                    ) values (?,'上海','2026-10-01','2026-10-03',
                              'LOCAL DEMO: Shanghai visit',true)
                    """, user);
            seedDocument(user, "EMAIL", "LOCAL DEMO - hotel invoice.pdf",
                    "HOTEL", new BigDecimal("480.00"));
            seedDocument(user, "DRIVE", "LOCAL DEMO - dinner receipt.pdf",
                    "MEAL", new BigDecimal("89.00"));
        }
        return Map.of(
                "sample", true,
                "city", "上海",
                "startDate", "2026-10-01",
                "endDate", "2026-10-03",
                "suggestedRequest", "请处理2026年10月1日至10月3日上海出差的报销",
                "message", "Sample data is explicitly labeled DEMO; no real email or booking was queried");
    }

    private void seedDocument(
            String user, String source, String name,
            String category, BigDecimal amount) {
        jdbc.update("""
                insert into local_document(
                    document_ref,user_id,source_type,file_name,
                    category,amount,city,issued_on,is_sample
                ) values (?,?,?, ?,?,?,'上海','2026-10-02',true)
                """, "local://document/" + UUID.randomUUID(), user,
                source, name, category, amount);
    }

    private void validateDocument(
            String source, String category, BigDecimal amount) {
        if (!SOURCES.contains(source) || !CATEGORIES.contains(category)) {
            throw new IllegalArgumentException("Invalid document source or category");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Amount must be greater than zero");
        }
    }

    public record DocumentInput(
            @NotBlank String sourceType,
            @NotBlank String fileName,
            @NotBlank String category,
            @NotNull @DecimalMin("0.01") BigDecimal amount,
            @NotBlank String city,
            @NotNull LocalDate issuedOn) {}

    public record TripInput(
            @NotBlank String city,
            @NotNull LocalDate startDate,
            @NotNull LocalDate endDate,
            @NotBlank String purpose) {}

    public record PolicyInput(
            @NotBlank String city,
            @NotBlank String category,
            @NotNull @DecimalMin("0.00") BigDecimal maxAmount,
            @NotBlank String description) {}
}
