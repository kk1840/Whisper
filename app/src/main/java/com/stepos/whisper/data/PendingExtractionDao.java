package com.stepos.whisper.data;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

@Dao
public interface PendingExtractionDao {
    @Insert
    long insert(PendingExtraction item);

    @Query("SELECT * FROM pending_extractions ORDER BY createdAt LIMIT :limit")
    List<PendingExtraction> getOldest(int limit);

    @Query("SELECT * FROM pending_extractions ORDER BY createdAt, id")
    List<PendingExtraction> getAll();

    @Query("SELECT COUNT(*) FROM pending_extractions WHERE userId = :userId AND startTime = :start AND endTime = :end")
    int countExact(long userId, long start, long end);

    @Query("SELECT COUNT(*) FROM pending_extractions WHERE id = :id")
    int exists(long id);

    @Query("UPDATE pending_extractions SET userId = :userId, attempts = 0 WHERE id = :id")
    void retarget(long id, long userId);

    @Query("UPDATE pending_extractions SET attempts = attempts + 1 WHERE id = :id")
    void incrementAttempts(long id);

    @Query("DELETE FROM pending_extractions WHERE id = :id")
    void delete(long id);

    @Query("SELECT COUNT(*) FROM pending_extractions")
    int count();

    @Query("DELETE FROM pending_extractions")
    void deleteAll();

    @Query("UPDATE pending_extractions SET userId = :to WHERE userId = :from")
    void reassignUser(long from, long to);
}
