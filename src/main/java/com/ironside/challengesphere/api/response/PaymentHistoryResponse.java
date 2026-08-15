package com.ironside.challengesphere.api.response;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class PaymentHistoryResponse {
    private List<PaymentHistoryItemResponse> payments;
    private int page;
    private int pageSize;
    private long totalCount;
    private int totalPages;
    private boolean hasMore;
}
