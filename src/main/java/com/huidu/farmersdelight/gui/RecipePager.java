package com.huidu.farmersdelight.gui;

/**
 * The page index shared by the recipe lists and the expanded ingredient list.
 */
final class RecipePager {

    private int page;

    int page() {
        return page;
    }

    void set(int page) {
        this.page = Math.max(0, page);
    }

    void reset() {
        this.page = 0;
    }

    int totalPages(int totalItems, int itemsPerPage) {
        if (itemsPerPage <= 0) {
            return 1;
        }
        return Math.max(1, (totalItems + itemsPerPage - 1) / itemsPerPage);
    }

    int clamp(int totalPages) {
        int last = Math.max(1, totalPages) - 1;
        if (page > last) {
            page = last;
        }
        return page;
    }

    int start(int itemsPerPage) {
        return page * Math.max(1, itemsPerPage);
    }
}
