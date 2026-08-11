package dev.watchcat.replay;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class ReplayIndex {
    private static final Logger LOGGER = Logger.getLogger(ReplayIndex.class.getName());
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path indexPath;
    private final CopyOnWriteArrayList<ReplayEntry> entries;
    private final ReentrantLock fileLock = new ReentrantLock();

    public ReplayIndex(Path indexPath) {
        this.indexPath = indexPath;
        this.entries = new CopyOnWriteArrayList<>();
        load();
    }

    private void load() {
        if (!Files.exists(indexPath)) {
            return;
        }

        try (Reader reader = Files.newBufferedReader(indexPath, StandardCharsets.UTF_8)) {
            Type listType = new TypeToken<ArrayList<ReplayEntry>>(){}.getType();
            List<ReplayEntry> loaded = GSON.fromJson(reader, listType);
            if (loaded != null) {
                entries.addAll(loaded);
            }
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Failed to load replay index from " + indexPath, e);
        }
    }

    public void addEntry(ReplayEntry entry) {
        entries.add(entry);
        save();
    }

    private void save() {
        fileLock.lock();
        try {
            Files.createDirectories(indexPath.getParent());
            try (Writer writer = Files.newBufferedWriter(indexPath, StandardCharsets.UTF_8)) {
                GSON.toJson(entries, writer);
            }
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Failed to save replay index to " + indexPath, e);
        } finally {
            fileLock.unlock();
        }
    }

    public boolean removeIf(Predicate<ReplayEntry> doomed) {
        List<ReplayEntry> toRemove = new ArrayList<>();
        for (ReplayEntry entry : entries) {
            if (doomed.test(entry)) {
                toRemove.add(entry);
            }
        }
        if (toRemove.isEmpty()) {
            return false;
        }
        entries.removeAll(toRemove);
        save();
        return true;
    }

    public List<ReplayEntry> getEntries() {
        return List.copyOf(entries);
    }
}
