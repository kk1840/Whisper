package com.stepos.whisper.data;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

@Dao
public interface NotificationJobDao {
    @Insert
    long insert(NotificationJob job);

    @Query("SELECT * FROM notification_jobs ORDER BY id")
    List<NotificationJob> getAll();

    @Query("SELECT * FROM notification_jobs WHERE id = :id")
    NotificationJob get(long id);

    @Query("DELETE FROM notification_jobs WHERE id = :id")
    void delete(long id);

    @Query("UPDATE notification_jobs SET attempts = attempts + 1 WHERE id = :id")
    void incrementAttempts(long id);

    @Query("DELETE FROM notification_jobs WHERE createdAt < :before")
    void deleteBefore(long before);
}
