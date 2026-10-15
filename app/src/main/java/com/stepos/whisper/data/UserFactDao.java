package com.stepos.whisper.data;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

@Dao
public interface UserFactDao {
    @Insert
    void insertAll(List<UserFact> facts);

    @Insert
    long insert(UserFact fact);

    @Query("SELECT * FROM user_facts WHERE userId = :userId AND done = 0 ORDER BY createdAt DESC, id DESC")
    List<UserFact> getPending(long userId);

    @Query("SELECT * FROM user_facts WHERE userId = :userId ORDER BY done, kind, createdAt DESC")
    LiveData<List<UserFact>> observeAll(long userId);

    /** 所有人未完成的待办和仍成立的事实，主界面再按类别分开显示。 */
    @Query("SELECT f.*, u.name AS userName, u.color AS userColor FROM user_facts f "
            + "JOIN users u ON u.id = f.userId WHERE f.done = 0 ORDER BY f.kind, "
            + "CASE WHEN f.kind = 0 THEN f.updatedAt ELSE f.createdAt END DESC, f.createdAt DESC, f.id DESC")
    LiveData<List<FactCard>> observePendingCards();

    /** 某人未完成的待办在前，仍成立的事实在后。 */
    @Query("SELECT f.*, u.name AS userName, u.color AS userColor FROM user_facts f "
            + "JOIN users u ON u.id = f.userId WHERE f.done = 0 AND f.userId = :userId "
            + "ORDER BY f.kind, CASE WHEN f.kind = 0 THEN f.updatedAt ELSE f.createdAt END DESC, f.createdAt DESC, f.id DESC")
    List<FactCard> getPendingCards(long userId);

    @Query("UPDATE user_facts SET done = :done, updatedAt = :time WHERE id = :id")
    void setDone(long id, boolean done, long time);

    @Query("UPDATE user_facts SET done = 1, updatedAt = :time WHERE userId = :userId AND id IN (:ids)")
    void markDone(long userId, List<Long> ids, long time);

    @Query("DELETE FROM user_facts WHERE id = :id")
    void delete(long id);

    @Query("UPDATE user_facts SET userId = :to WHERE userId = :from")
    void reassignUser(long from, long to);

    /** 待办合并：保留的那条写入合并后的内容；被吸收的那条另行 {@link #delete}。 */
    @Query("UPDATE user_facts SET content = :content, dueTime = :time, location = :location, people = :people, "
            + "updatedAt = :at WHERE id = :id")
    void applyMerge(long id, String content, String time, String location, String people, long at);
}
