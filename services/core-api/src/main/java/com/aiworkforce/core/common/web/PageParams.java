package com.aiworkforce.core.common.web;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/** Pagination limits shared by every list endpoint ({@code ?page=0&size=20}, size at most 100). */
public final class PageParams {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    private PageParams() {
    }

    public static PageRequest of(int page, int size, Sort sort) {
        return PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_SIZE), sort);
    }
}
