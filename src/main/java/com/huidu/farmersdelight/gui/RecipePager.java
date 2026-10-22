package com.huidu.farmersdelight.gui;

/**
 * The page index shared by the recipe lists and the expanded ingredient list. The arithmetic itself lives in
 * {@link GuiPaging} so the recipe book and this pager cannot drift apart.
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
        return GuiPaging.pageCount(totalItems, itemsPerPage);
    }

    int clamp(int totalPages) {
        page = GuiPaging.clampPage(page, totalPages);
        return page;
    }

    int start(int itemsPerPage) {
        return GuiPaging.startIndex(page, itemsPerPage);
    }
}
