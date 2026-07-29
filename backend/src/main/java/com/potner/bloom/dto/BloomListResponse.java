package com.potner.bloom.dto;

import java.util.List;

public record BloomListResponse(
        List<BloomResponse> blooms,
        int page,
        int size,
        long totalElements,
        int totalPages,
        long unreadCount
) {

    public BloomListResponse {
        blooms = List.copyOf(blooms);
    }
}
