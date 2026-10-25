package com.huidu.farmersdelight.gui;

import net.kyori.adventure.text.Component;

/**
 * Page arithmetic and the `{page}/{total}` title substitution shared by the list-style GUIs. Keeping the
 * rules in one place is what makes the recipe view, the addon recipe book and the expanded ingredient list agree
 * on clamping and page numbering.
 */
public final class GuiPaging {

    private GuiPaging() {
    }

    /** Number of pages for the item count; at least one so an empty list still renders page 1 of 1. */
    public static int pageCount(int totalItems, int itemsPerPage) {
        if (itemsPerPage <= 0) {
            return 1;
        }
        return Math.max(1, (totalItems + itemsPerPage - 1) / itemsPerPage);
    }

    /** The requested page, clamped into `[0, pageCount - 1]`. */
    public static int clampPage(int page, int pageCount) {
        int last = Math.max(1, pageCount) - 1;
        return Math.min(Math.max(0, page), last);
    }

    /** Index of the first item on the page. */
    public static int startIndex(int page, int itemsPerPage) {
        return Math.max(0, page) * Math.max(1, itemsPerPage);
    }

    /**
     * Substitutes the `{page}` and `{total}` literals in a list title. A title without them is
     * returned unchanged, so an addon's own title keeps working.
     */
    public static Component withPageInfo(Component title, int page, int pageCount) {
        return title
                .replaceText(builder -> builder.matchLiteral("{page}").replacement(Integer.toString(page)))
                .replaceText(builder -> builder.matchLiteral("{total}").replacement(Integer.toString(pageCount)));
    }
}
