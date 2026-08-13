package anon.def9a2a4.pipes;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Tick and wall-clock due buckets owned by one world task thread. */
final class TransferSchedule<K> {

    private final DueQueue<K> active = new DueQueue<>();
    private final DueQueue<K> sleeping = new DueQueue<>();

    void schedule(K key, long dueTick) {
        sleeping.cancel(key);
        active.schedule(key, dueTick);
    }

    void sleep(K key, long wakeTimeMillis) {
        active.cancel(key);
        sleeping.schedule(key, wakeTimeMillis);
    }

    boolean isSleeping(K key) {
        return sleeping.contains(key);
    }

    List<K> pollDue(long currentTick) {
        return active.pollDue(currentTick);
    }

    List<K> pollWoken(long nowMillis) {
        return sleeping.pollDue(nowMillis);
    }

    void cancel(K key) {
        active.cancel(key);
        sleeping.cancel(key);
    }

    void clear() {
        active.clear();
        sleeping.clear();
    }

    static long firstDueTick(long earliestTick, int phase, int intervalTicks) {
        long interval = Math.max(1, intervalTicks);
        long delay = Math.floorMod(phase - Math.floorMod(earliestTick, interval), interval);
        return earliestTick + delay;
    }

    static long nextDueTick(long currentTick, Long lastTick, int phase, int intervalTicks) {
        long interval = Math.max(1, intervalTicks);
        return lastTick == null
                ? firstDueTick(currentTick, phase, intervalTicks)
                : Math.max(currentTick, lastTick + interval);
    }

    private static final class DueQueue<K> {
        private final NavigableMap<Long, Set<K>> keysByDue = new TreeMap<>();
        private final Map<K, Long> dueByKey = new HashMap<>();

        void schedule(K key, long due) {
            Long previous = dueByKey.get(key);
            if (Objects.equals(previous, due)) return;
            cancel(key);
            dueByKey.put(key, due);
            keysByDue.computeIfAbsent(due, ignored -> new LinkedHashSet<>()).add(key);
        }

        void cancel(K key) {
            Long due = dueByKey.remove(key);
            if (due == null) return;

            Set<K> keys = keysByDue.get(due);
            if (keys == null) return;
            keys.remove(key);
            if (keys.isEmpty()) keysByDue.remove(due);
        }

        boolean contains(K key) {
            return dueByKey.containsKey(key);
        }

        List<K> pollDue(long now) {
            if (keysByDue.isEmpty() || keysByDue.firstKey() > now) return List.of();

            List<K> dueKeys = new ArrayList<>();
            while (!keysByDue.isEmpty() && keysByDue.firstKey() <= now) {
                Map.Entry<Long, Set<K>> entry = keysByDue.pollFirstEntry();
                for (K key : entry.getValue()) {
                    if (dueByKey.remove(key, entry.getKey())) dueKeys.add(key);
                }
            }
            return dueKeys;
        }

        void clear() {
            keysByDue.clear();
            dueByKey.clear();
        }
    }
}
