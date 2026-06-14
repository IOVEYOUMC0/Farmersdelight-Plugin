package com.huidu.farmersdelight.recipe;

import com.huidu.farmersdelight.util.ItemUtils;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 将内存中的配方模型转换回配方解析器能够理解的 YAML 字符串形式
 * （即 RecipeParsingSupport 与管理器的 parseRecipe 方法的逆操作）。
 *
 * <p>这里的字符串转换是纯函数（不涉及 Bukkit/CraftEngine 状态），因此可以通过单元测试
 * 验证往返转换的一致性。物品解析（itemIdString(ItemStack)）是唯一会
 * 接触 CraftEngine 的方法，正因如此将其单独拆分出来。
 */
public final class RecipeSerializer {

    private RecipeSerializer() {
    }

    /**
     * 将一个配料（cooking-pot 的配料槽，或 cutting-board 的输入）序列化为其 YAML
     * 字符串：一个物品 key、一个带可选 ,!exclusions 的 #tag，或者 a|b 形式的多选项。
     */
    public static String serializeIngredient(RecipeIngredient ingredient) {
        if (ingredient instanceof RecipeIngredient.Item item) {
            return item.key().toString();
        }
        if (ingredient instanceof RecipeIngredient.Tag tag) {
            return serializeTag(tag.key(), tag.excludedItems(), tag.excludedTags());
        }
        if (ingredient instanceof RecipeIngredient.Choice choice) {
            StringBuilder builder = new StringBuilder();
            List<RecipeIngredient> options = choice.options();
            for (int i = 0; i < options.size(); i++) {
                if (i > 0) {
                    builder.append('|');
                }
                builder.append(serializeIngredient(options.get(i)));
            }
            return builder.toString();
        }
        return "";
    }

    /**
     * 序列化 cutting-board 的工具需求。工具 key 既可以作为物品 id 匹配，也可以作为 tag 匹配，
     * 两者一视同仁，因此它以原始 key 加上任意 ,!exclusions 的形式输出。
     */
    public static String serializeTool(CuttingBoardRecipe.ToolRequirement tool) {
        return serializeKeyWithExclusions(tool.getKey().toString(), tool.getExcludedItems(), tool.getExcludedTags());
    }

    private static String serializeTag(Key key, Set<Key> excludedItems, Set<Key> excludedTags) {
        return serializeKeyWithExclusions("#" + key, excludedItems, excludedTags);
    }

    private static String serializeKeyWithExclusions(String base, Set<Key> excludedItems, Set<Key> excludedTags) {
        StringBuilder builder = new StringBuilder(base);
        // 对排除项排序，使序列化结果具有确定性（集合本身是无序的）。
        List<String> exclusions = new ArrayList<>();
        for (Key excludedItem : excludedItems) {
            exclusions.add(",!" + excludedItem);
        }
        for (Key excludedTag : excludedTags) {
            exclusions.add(",!#" + excludedTag);
        }
        Collections.sort(exclusions);
        for (String exclusion : exclusions) {
            builder.append(exclusion);
        }
        return builder.toString();
    }

    /**
     * 将一个物品堆叠解析为配方的物品 id 字符串：若存在则使用其 CraftEngine 自定义 id，
     * 否则使用 minecraft:<material>。
     */
    public static String itemIdString(ItemStack item) {
        if (item == null) {
            return null;
        }
        String customId = ItemUtils.getCustomItemId(item);
        if (customId != null) {
            return customId;
        }
        return "minecraft:" + item.getType().name().toLowerCase(Locale.ROOT);
    }
}
