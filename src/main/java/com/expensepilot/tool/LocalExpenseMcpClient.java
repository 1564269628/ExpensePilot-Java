package com.expensepilot.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Local database-backed implementation of the SAME ExpenseMcpClient contract
 * used by the production Spring AI MCP adapter.
 *
 * All data originates from the local workspace or explicit demo seed data.
 * No fake Gmail/Drive/travel integrations or fictitious real-world submissions.
 * Real Groq inference is handled separately by Planner/ChatClient.
 */
@Component
@Primary
@Profile("local")
public class LocalExpenseMcpClient implements ExpenseMcpClient {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final McpToolResultValidator validator;

    public LocalExpenseMcpClient(
            JdbcTemplate jdbc,
            ObjectMapper json,
            McpToolResultValidator validator) {
        this.jdbc = jdbc;
        this.json = json;
        this.validator = validator;
    }

    @Override
    public ToolResult invoke(String toolName, Map<String, Object> arguments) {
        ToolResult result = switch (toolName) {
            case "search_email" -> searchEmail(arguments);
            case "search_drive" -> searchDrive(arguments);
            case "query_travel" -> queryTravel(arguments);
            case "parse_invoices" -> parseInvoices(arguments);
            case "validate_materials" -> validateMaterials(arguments);
            case "check_expense_policy" -> checkPolicy(arguments);
            case "build_expense_report_draft" -> buildDraft(arguments);
            case "submit_expense_report" -> submit(arguments);
            case "query_expense_submission" -> submissionByRequestId(arguments);
            case "send_notification" -> sendNotification(arguments);
            case "query_notification" -> notificationByRequestId(arguments);
            default -> throw new McpContractException(
                    "Local workspace has no tool: " + toolName);
        };
        validator.validate(toolName, result);
        return result;
    }

    @Override
    public ToolResult queryByRequestId(String toolName, String requestId) {
        return switch (toolName) {
            case "submit_expense_report" -> invoke(
                    "query_expense_submission", Map.of("requestId", requestId));
            case "send_notification" -> invoke(
                    "query_notification", Map.of("requestId", requestId));
            default -> throw new IllegalArgumentException(
                    "No reconciliation tool for " + toolName);
        };
    }

    private ToolResult searchEmail(Map<String, Object> a) {
        List<Map<String, Object>> messages = new ArrayList<>();
        for (Document d : documents(a, List.of("EMAIL"))) {
            messages.add(Map.of(
                    "messageId", d.ref(),
                    "subject", d.fileName(),
                    "sentAt", d.date().toString() + "T12:00:00+08:00",
                    "attachmentRefs", List.of(d.ref())));
        }
        return ok(Map.of("messages", messages, "source", "LOCAL_WORKSPACE"));
    }

    private ToolResult searchDrive(Map<String, Object> a) {
        List<Map<String, Object>> files = new ArrayList<>();
        for (Document d : documents(a, List.of("DRIVE", "UPLOAD"))) {
            files.add(Map.of(
                    "fileRef", d.ref(),
                    "fileName", d.fileName(),
                    "modifiedAt", d.date().toString() + "T12:00:00+08:00"));
        }
        return ok(Map.of("files", files, "source", "LOCAL_WORKSPACE"));
    }

    private ToolResult queryTravel(Map<String, Object> a) {
        String userId = text(a, "userId");
        String start = text(a, "startDate");
        String end = text(a, "endDate");
        String city = text(a, "city");
        return ok(Map.of(
                "bookings", trips(userId, city, start, end),
                "city", city,
                "startDate", start,
                "endDate", end,
                "source", "LOCAL_WORKSPACE"));
    }

    private List<Map<String, Object>> trips(
            String userId, String city, String start, String end) {
        return jdbc.query("""
                select id,city,start_date,end_date,purpose,is_sample
                  from local_trip
                 where user_id=? and city=?
                   and start_date<=? and end_date>=?
                 order by id desc
                """, (rs, i) -> Map.<String, Object>of(
                    "bookingId", "local-trip-" + rs.getLong("id"),
                    "city", rs.getString("city"),
                    "startDate", rs.getDate("start_date").toLocalDate().toString(),
                    "endDate", rs.getDate("end_date").toLocalDate().toString(),
                    "purpose", rs.getString("purpose"),
                    "sample", rs.getBoolean("is_sample")),
                userId, city, Date.valueOf(end), Date.valueOf(start));
    }

    private ToolResult parseInvoices(Map<String, Object> a) {
        String userId = text(a, "userId");
        Set<String> refs = new LinkedHashSet<>();
        for (Map<String, Object> m : rows(object(a, "email"), "messages")) {
            Object attachments = m.get("attachmentRefs");
            if (attachments instanceof List<?> list) {
                for (Object ref : list) refs.add(String.valueOf(ref));
            }
        }
        for (Map<String, Object> m : rows(object(a, "drive"), "files")) {
            refs.add(String.valueOf(m.get("fileRef")));
        }
        List<Map<String, Object>> invoices = new ArrayList<>();
        for (String ref : refs) {
            invoices.add(documentByRef(userId, ref));
        }
        return ok(invoiceSummary(invoices));
    }

    private ToolResult validateMaterials(Map<String, Object> a) {
        String userId = text(a, "userId");
        List<Map<String, Object>> invoices = new ArrayList<>(
                rows(object(a, "invoices"), "invoices"));

        Object supplements = a.getOrDefault("supplementalMaterials", List.of());
        if (!(supplements instanceof List<?> refs)) {
            throw new McpContractException("supplementalMaterials must be a list");
        }
        Set<String> seen = new LinkedHashSet<>();
        for (Map<String, Object> i : invoices) seen.add(String.valueOf(i.get("fileRef")));
        for (Object ref : refs) {
            String documentRef = String.valueOf(ref);
            if (seen.add(documentRef)) invoices.add(documentByRef(userId, documentRef));
        }

        Map<String, Object> initialTravel = object(a, "travel");
        String city = text(initialTravel, "city");
        String start = text(initialTravel, "startDate");
        String end = text(initialTravel, "endDate");
        List<Map<String, Object>> bookings = trips(userId, city, start, end);

        List<String> missing = new ArrayList<>();
        if (invoices.isEmpty()) missing.add("至少一份发票或费用材料");
        if (bookings.isEmpty()) missing.add("差旅行程记录");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("complete", missing.isEmpty());
        result.put("missingItems", missing);
        result.put("matchedItems", invoices.stream().map(i -> i.get("fileRef")).toList());
        result.put("validatedInvoices", invoiceSummary(invoices));
        result.put("validatedTravel", Map.of(
                "bookings", bookings,
                "city", city,
                "startDate", start,
                "endDate", end,
                "source", "LOCAL_WORKSPACE"));
        return ok(result);
    }

    private ToolResult checkPolicy(Map<String, Object> a) {
        String city = text(a, "city");
        List<Map<String, Object>> invoices = rows(object(a, "invoices"), "invoices");
        List<Map<String, Object>> violations = new ArrayList<>();
        for (Map<String, Object> invoice : invoices) {
            String category = text(invoice, "category").toUpperCase();
            BigDecimal amount = decimal(invoice.get("amount"));
            List<Map<String, Object>> policy = jdbc.query("""
                    select id,city,category,max_amount,description
                      from local_policy_rule
                     where (city=? or city='*') and category=?
                     order by case when city=? then 0 else 1 end
                     limit 1
                    """, (rs, i) -> Map.<String, Object>of(
                        "ruleId", "LOCAL-" + rs.getLong("id"),
                        "maxAmount", rs.getBigDecimal("max_amount"),
                        "description", rs.getString("description")),
                    city, category, city);
            if (policy.isEmpty()) {
                throw new McpContractException(
                        "Local policy rule missing for category: " + category);
            }
            BigDecimal limit = (BigDecimal) policy.get(0).get("maxAmount");
            if (amount.compareTo(limit) > 0) {
                violations.add(Map.of(
                        "ruleId", policy.get(0).get("ruleId"),
                        "description", policy.get(0).get("description"),
                        "category", category,
                        "fileRef", String.valueOf(invoice.get("fileRef")),
                        "actual", amount,
                        "limit", limit,
                        "action", "REQUIRE_APPROVAL"));
            }
        }
        return ok(Map.of(
                "compliant", violations.isEmpty(),
                "violations", violations,
                "ruleSource", "LOCAL_DEMO_POLICY"));
    }

    private ToolResult buildDraft(Map<String, Object> a) {
        Map<String, Object> invoices = object(a, "invoices");
        BigDecimal amount = BigDecimal.ZERO;
        for (Map<String, Object> row : rows(invoices, "invoices")) {
            amount = amount.add(decimal(row.get("amount")));
        }
        return ok(Map.of(
                "draftId", "draft-" + UUID.randomUUID(),
                "userId", text(a, "userId"),
                "requestText", text(a, "requestText"),
                "invoices", invoices.getOrDefault("invoices", List.of()),
                "trip", object(a, "trip"),
                "policy", object(a, "policy"),
                "totalAmount", amount,
                "currency", "CNY",
                "status", "DRAFT"));
    }

    private ToolResult submit(Map<String, Object> a) {
        String requestId = text(a, "requestId");
        String userId = text(a, "userId");
        String number = "LOCAL-EXP-"
                + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
                + "-" + UUID.randomUUID().toString().substring(0, 10).toUpperCase();
        jdbc.update("""
                insert into local_expense_submission(
                  request_id,user_id,business_no,draft_json
                ) values (?,?,?,cast(? as json))
                on duplicate key update request_id=request_id
                """, requestId, userId, number, toJson(object(a, "reportDraft")));
        return submissionByRequestId(Map.of("requestId", requestId));
    }

    private ToolResult submissionByRequestId(Map<String, Object> a) {
        return jdbc.query("""
                select business_no,user_id from local_expense_submission
                 where request_id=?
                """, rs -> {
                    if (!rs.next()) return failed("NOT_FOUND", "No local submission for requestId");
                    String businessNo = rs.getString("business_no");
                    return new ToolResult(true, "OK", "Local expense submitted",
                            Map.of("status", "SUBMITTED", "source", "LOCAL_WORKSPACE"),
                            businessNo);
                }, text(a, "requestId"));
    }

    private ToolResult sendNotification(Map<String, Object> a) {
        String requestId = text(a, "requestId");
        String eventId = text(a, "eventId");
        String businessNo = text(a, "businessNo");
        jdbc.update("""
                insert into local_notification(request_id,user_id,business_no,event_id)
                values (?,?,?,?)
                on duplicate key update request_id=request_id
                """, requestId, text(a, "userId"), businessNo, eventId);
        return notificationByRequestId(Map.of("requestId", requestId));
    }

    private ToolResult notificationByRequestId(Map<String, Object> a) {
        return jdbc.query("""
                select business_no from local_notification where request_id=?
                """, rs -> {
                    if (!rs.next()) return failed("NOT_FOUND", "No local notification for requestId");
                    return new ToolResult(true, "OK", "Recorded local notification",
                            Map.of("status", "RECORDED", "source", "LOCAL_WORKSPACE"),
                            rs.getString("business_no"));
                }, text(a, "requestId"));
    }

    private List<Document> documents(Map<String, Object> a, List<String> sources) {
        String sql = """
                select document_ref,file_name,city,issued_on,category,amount
                  from local_document
                 where user_id=? and city=? and issued_on between ? and ?
                """;
        var result = jdbc.query(sql, (rs, i) -> new Document(
                rs.getString("document_ref"),
                rs.getString("file_name"),
                rs.getString("city"),
                rs.getDate("issued_on").toLocalDate(),
                rs.getString("category"),
                rs.getBigDecimal("amount")),
                text(a, "userId"), text(a, "city"),
                Date.valueOf(text(a, "startDate")),
                Date.valueOf(text(a, "endDate")));
        return result.stream().filter(d -> sources.contains(sourceOf(d.ref()))).toList();
    }

    private String sourceOf(String ref) {
        return jdbc.queryForObject(
                "select source_type from local_document where document_ref=?",
                String.class, ref);
    }

    private Map<String, Object> documentByRef(String userId, String ref) {
        return jdbc.query("""
                select document_ref,file_name,city,issued_on,category,amount
                  from local_document where user_id=? and document_ref=?
                """, rs -> {
                    if (!rs.next()) {
                        throw new McpContractException(
                                "Material reference does not belong to authenticated user");
                    }
                    return Map.<String, Object>of(
                            "fileRef", rs.getString("document_ref"),
                            "fileName", rs.getString("file_name"),
                            "city", rs.getString("city"),
                            "issuedOn", rs.getDate("issued_on").toLocalDate().toString(),
                            "category", rs.getString("category"),
                            "amount", rs.getBigDecimal("amount"),
                            "verification", "MANUALLY_ENTERED_LOCAL_DATA");
                }, userId, ref);
    }

    private Map<String, Object> invoiceSummary(List<Map<String, Object>> invoices) {
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> invoice : invoices) {
            total = total.add(decimal(invoice.get("amount")));
        }
        return Map.of(
                "invoices", invoices,
                "totalAmount", total,
                "source", "LOCAL_WORKSPACE");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> object(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (!(value instanceof Map<?, ?>)) {
            throw new McpContractException(key + " is not a JSON object");
        }
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rows(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (!(value instanceof List<?> list)) {
            throw new McpContractException(key + " is not a JSON array");
        }
        return list.stream().map(v -> {
            if (!(v instanceof Map<?, ?>)) {
                throw new McpContractException(key + " contains a non-object value");
            }
            return (Map<String, Object>) v;
        }).toList();
    }

    private String text(Map<String, Object> args, String key) {
        Object v = args.get(key);
        if (v == null || String.valueOf(v).isBlank()) {
            throw new McpContractException(key + " is required");
        }
        return String.valueOf(v);
    }

    private BigDecimal decimal(Object number) {
        try {
            return new BigDecimal(String.valueOf(number));
        }
        catch (NumberFormatException ex) {
            throw new McpContractException("Invalid document monetary amount", ex);
        }
    }

    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        }
        catch (JsonProcessingException ex) {
            throw new McpContractException("Cannot serialize local report draft", ex);
        }
    }

    private ToolResult ok(Map<String, Object> data) {
        return new ToolResult(true, "OK", "Local operation completed", data, null);
    }

    private ToolResult failed(String code, String message) {
        return new ToolResult(false, code, message, Map.of(), null);
    }

    private record Document(
            String ref,
            String fileName,
            String city,
            LocalDate date,
            String category,
            BigDecimal amount) {}
}
