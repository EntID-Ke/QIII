package io.github.qqmodulemanager.core;

import java.util.*;
import java.util.function.Function;

/** Pure transformation: never changes an action object or the caller's input list. */
public final class MenuOrder {
    private MenuOrder() {}

    public static <T> List<T> apply(List<T> input, Function<T, String> key,
                                    List<String> order, Set<String> hidden) {
        Map<String, Integer> ranks = new HashMap<>();
        for (int i = 0; i < order.size(); i++) ranks.putIfAbsent(order.get(i), i);
        // An ambiguous identity must not hide multiple unrelated actions.
        Map<String, Integer> counts = new HashMap<>();
        for (T item : input) counts.merge(key.apply(item), 1, Integer::sum);
        List<T> result = new ArrayList<>();
        for (T item : input) {
            String id = key.apply(item);
            if (!hidden.contains(id) || counts.get(id) > 1) result.add(item);
        }
        result.sort(Comparator.comparingInt(item -> {
            String id = key.apply(item);
            return counts.get(id) > 1 ? Integer.MAX_VALUE : ranks.getOrDefault(id, Integer.MAX_VALUE);
        }));
        return result;
    }
}
