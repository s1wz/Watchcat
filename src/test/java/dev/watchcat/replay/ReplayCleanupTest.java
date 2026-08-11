package dev.watchcat.replay;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayCleanupTest {

    private static final long NOW = 1_700_000_000_000L;
    private static final long HOUR = 3_600_000L;
    private static final long MB = 1024L * 1024L;

    private static ReplayCleanup.Candidate aged(String name, long hoursOld, long megabytes) {
        return new ReplayCleanup.Candidate(Path.of(name), NOW - hoursOld * HOUR, megabytes * MB, false);
    }

    private static ReplayCleanup.Candidate protectedAged(String name, long hoursOld, long megabytes) {
        return new ReplayCleanup.Candidate(Path.of(name), NOW - hoursOld * HOUR, megabytes * MB, true);
    }

    private static List<String> names(List<ReplayCleanup.Candidate> candidates) {
        List<String> out = new ArrayList<>();
        for (ReplayCleanup.Candidate candidate : candidates) {
            out.add(candidate.getFile().toString());
        }
        return out;
    }

    private static CleanupPolicy policy(boolean enabled, int retentionHours, int minRetentionHours, int maxMb) {
        return new CleanupPolicy(enabled, retentionHours, minRetentionHours, maxMb, true);
    }

    @Test
    void deletesNothingWhileDisabled() {
        List<ReplayCleanup.Candidate> all = List.of(aged("ancient.mcpr", 500, 10));

        assertTrue(ReplayCleanup.selectForDeletion(all, policy(false, 36, 12, 100), NOW).isEmpty(),
                "a disabled policy must never delete, however old the replay");
    }

    @Test
    void deletesOnlyReplaysPastTheRetentionWindow() {
        List<ReplayCleanup.Candidate> all = List.of(
                aged("fresh.mcpr", 1, 10),
                aged("yesterday.mcpr", 35, 10),
                aged("stale.mcpr", 37, 10));

        List<ReplayCleanup.Candidate> doomed =
                ReplayCleanup.selectForDeletion(all, policy(true, 36, 12, 100_000), NOW);

        assertEquals(List.of("stale.mcpr"), names(doomed));
    }

    @Test
    void neverDeletesAProtectedReplayHoweverOld() {
        List<ReplayCleanup.Candidate> all = List.of(
                protectedAged("flagged.mcpr", 5000, 10),
                aged("ordinary.mcpr", 40, 10));

        List<ReplayCleanup.Candidate> doomed =
                ReplayCleanup.selectForDeletion(all, policy(true, 36, 12, 100_000), NOW);

        assertEquals(List.of("ordinary.mcpr"), names(doomed));
    }

    @Test
    void deletesOldestFirstWhenOverTheStorageCap() {
        List<ReplayCleanup.Candidate> all = List.of(
                aged("newest.mcpr", 13, 40),
                aged("middle.mcpr", 20, 40),
                aged("oldest.mcpr", 30, 40));

        List<ReplayCleanup.Candidate> doomed =
                ReplayCleanup.selectForDeletion(all, policy(true, 36, 12, 100), NOW);

        assertEquals(List.of("oldest.mcpr"), names(doomed),
                "120 MB over a 100 MB cap needs exactly one deletion, the oldest");
    }

    @Test
    void storagePressureStopsAtTheMinimumRetentionFloor() {
        List<ReplayCleanup.Candidate> all = List.of(
                aged("recent-a.mcpr", 1, 400),
                aged("recent-b.mcpr", 2, 400));

        List<ReplayCleanup.Candidate> doomed =
                ReplayCleanup.selectForDeletion(all, policy(true, 36, 12, 100), NOW);

        assertTrue(doomed.isEmpty(),
                "being over the cap must not justify deleting evidence younger than the floor");
    }

    @Test
    void protectedReplaysStillCountTowardsTheCap() {
        List<ReplayCleanup.Candidate> all = List.of(
                protectedAged("flagged.mcpr", 20, 90),
                aged("ordinary.mcpr", 20, 20));

        List<ReplayCleanup.Candidate> doomed =
                ReplayCleanup.selectForDeletion(all, policy(true, 36, 12, 100), NOW);

        assertEquals(List.of("ordinary.mcpr"), names(doomed),
                "protected files occupy disk, so they push deletable ones over the cap");
    }

    @Test
    void aFileIsNeverSelectedTwiceWhenBothRulesApply() {
        List<ReplayCleanup.Candidate> all = List.of(
                aged("stale.mcpr", 100, 400),
                aged("recent.mcpr", 13, 20));

        List<ReplayCleanup.Candidate> doomed =
                ReplayCleanup.selectForDeletion(all, policy(true, 36, 12, 100), NOW);

        assertEquals(List.of("stale.mcpr"), names(doomed),
                "the age pass frees enough, so the cap pass must find nothing left to do");
    }

    @Test
    void aZeroRetentionSettingDisablesTheAgeRuleRatherThanDeletingEverything() {
        List<ReplayCleanup.Candidate> all = List.of(aged("fresh.mcpr", 1, 10));

        List<ReplayCleanup.Candidate> doomed =
                ReplayCleanup.selectForDeletion(all, policy(true, 0, 12, 100_000), NOW);

        assertTrue(doomed.isEmpty(),
                "retention-hours: 0 must mean 'no age limit', not 'delete on sight'");
    }
}
