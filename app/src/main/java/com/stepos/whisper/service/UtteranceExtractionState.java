package com.stepos.whisper.service;

import com.stepos.whisper.data.Utterance;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Sentence-local attribution gates. All operations share the manual/voiceprint commit lock. */
public final class UtteranceExtractionState {
    private static final class Key {
        final long start, end;
        Key(long start, long end) { this.start = start; this.end = end; }
        @Override public boolean equals(Object other) {
            return other instanceof Key && start == ((Key) other).start && end == ((Key) other).end;
        }
        @Override public int hashCode() { return 31 * Long.hashCode(start) + Long.hashCode(end); }
    }

    private static final class Entry {
        long revision;
        boolean pending;
    }

    public static final class Snapshot {
        private final long epoch;
        private final Map<Key, Long> revisions;
        private Snapshot(long epoch, Map<Key, Long> revisions) {
            this.epoch = epoch;
            this.revisions = revisions;
        }
    }

    private final Map<Key, Entry> entries = new HashMap<>();
    private long epoch;

    /** Called before the attribution operation is queued, including manual intent on the UI thread. */
    public long pending(long start, long end) {
        synchronized (ServiceState.class) {
            Entry entry = entries.computeIfAbsent(new Key(start, end), key -> new Entry());
            entry.pending = true;
            return ++entry.revision;
        }
    }

    public boolean isCurrent(long start, long end, long revision) {
        synchronized (ServiceState.class) {
            Entry entry = entries.get(new Key(start, end));
            return entry != null && entry.revision == revision;
        }
    }

    /** Only the latest manual assignment can release its gate. */
    public boolean ready(long start, long end, long revision) {
        synchronized (ServiceState.class) {
            Entry entry = entries.get(new Key(start, end));
            if (entry == null || entry.revision != revision) return false;
            entry.pending = false;
            return true;
        }
    }

    public void ready(long start, long end) {
        synchronized (ServiceState.class) {
            Entry entry = entries.computeIfAbsent(new Key(start, end), key -> new Entry());
            entry.pending = false;
            entry.revision++;
        }
    }

    public boolean isReady(long start, long end) {
        synchronized (ServiceState.class) {
            Entry entry = entries.get(new Key(start, end));
            return entry == null || !entry.pending;
        }
    }

    public Snapshot snapshot(List<Utterance> rows) {
        synchronized (ServiceState.class) {
            Map<Key, Long> revisions = new HashMap<>();
            for (Utterance row : rows) {
                Key key = new Key(row.startTime, row.endTime);
                Entry entry = entries.get(key);
                if (entry != null && entry.pending) return null;
                revisions.put(key, entry == null ? 0L : entry.revision);
            }
            return new Snapshot(epoch, revisions);
        }
    }

    public boolean isValid(Snapshot snapshot) {
        synchronized (ServiceState.class) {
            if (snapshot == null || snapshot.epoch != epoch) return false;
            for (Map.Entry<Key, Long> revision : snapshot.revisions.entrySet()) {
                Entry entry = entries.get(revision.getKey());
                if (entry != null && entry.pending) return false;
                if ((entry == null ? 0L : entry.revision) != revision.getValue()) return false;
            }
            return true;
        }
    }

    public void clear() {
        synchronized (ServiceState.class) {
            entries.clear();
            epoch++;
        }
    }
}
