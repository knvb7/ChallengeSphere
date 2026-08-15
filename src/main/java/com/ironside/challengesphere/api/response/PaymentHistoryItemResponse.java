package com.ironside.challengesphere.api.response;

import com.ironside.challengesphere.model.PaymentHistory;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.sql.Timestamp;

@Data
@Builder
public class PaymentHistoryItemResponse {
    private Long id;
    private String eventId;
    private String title;
    private String eventType;
    private String status;
    private String transactionId;
    private String productId;
    private String newProductId;
    private String store;
    private String environment;
    private BigDecimal amount;
    private String currency;
    private String periodType;
    private String cancellationReason;
    private String expirationReason;
    private Timestamp purchasedAt;
    private Timestamp expiresAt;
    private Timestamp eventAt;

    public static PaymentHistoryItemResponse from(PaymentHistory history) {
        return PaymentHistoryItemResponse.builder()
                .id(history.getId())
                .eventId(history.getWebhookEventId())
                .title(titleFor(history.getEventType()))
                .eventType(history.getEventType())
                .status(history.getStatus())
                .transactionId(history.getTransactionId())
                .productId(history.getProductId())
                .newProductId(history.getNewProductId())
                .store(history.getStore())
                .environment(history.getEnvironment())
                .amount(history.getAmount())
                .currency(history.getCurrency())
                .periodType(history.getPeriodType())
                .cancellationReason(history.getCancellationReason())
                .expirationReason(history.getExpirationReason())
                .purchasedAt(history.getPurchasedAt())
                .expiresAt(history.getExpiresAt())
                .eventAt(history.getEventAt())
                .build();
    }

    private static String titleFor(String eventType) {
        if (eventType == null) return "Premium update";
        return switch (eventType) {
            case "INITIAL_PURCHASE" -> "Premium started";
            case "RENEWAL" -> "Premium renewed";
            case "CANCELLATION" -> "Renewal cancelled";
            case "UNCANCELLATION" -> "Renewal resumed";
            case "NON_RENEWING_PURCHASE" -> "Purchase completed";
            case "SUBSCRIPTION_PAUSED" -> "Subscription pause scheduled";
            case "EXPIRATION" -> "Premium expired";
            case "BILLING_ISSUE" -> "Payment issue";
            case "PRODUCT_CHANGE" -> "Plan change scheduled";
            case "SUBSCRIPTION_EXTENDED" -> "Premium extended";
            case "REFUND_REVERSED" -> "Refund reversed";
            case "INVOICE_ISSUANCE" -> "Payment pending";
            default -> "Premium update";
        };
    }
}
