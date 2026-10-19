package com.huidu.farmersdelight.gui;

/**
 * The page index shared by the recipe lists and the expanded ingredient list.
 *
 * The lists used to compute the page count with four slightly different formulas, some with a one-page
 * minimum and some without, so an empty list could collapse to page zero and the title showed "page 1/0".
 * Every list now goes through these methods, so an empty list always has one page and the index is
 * clamped after the list shrinks.
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
