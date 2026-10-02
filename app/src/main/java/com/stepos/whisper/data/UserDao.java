package com.stepos.whisper.data;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface UserDao {
    @Insert
    long insert(User user);

    @Update
    void update(User user);

    @Query("DELETE FROM users WHERE id = :id")
    void delete(long id);

    @Query("DELETE FROM users WHERE isOwner = 0")
    void deleteAllExceptOwner();

    @Query("DELETE FROM users")
    void deleteAll();

    @Query("SELECT * FROM users WHERE id = :id")
    User get(long id);

    @Query("SELECT * FROM users WHERE id = :id")
    LiveData<User> observe(long id);

    @Query("SELECT * FROM users ORDER BY id")
    List<User> getAll();

    @Query("SELECT * FROM users WHERE isOwner = 1 LIMIT 1")
    User getOwner();

    @Query("SELECT * FROM users WHERE name = :name LIMIT 1")
    User findByName(String name);

    @Query("SELECT * FROM users ORDER BY isOwner DESC, lastSeenAt DESC")
    LiveData<List<User>> observeAll();

    @Query("SELECT color FROM users")
    List<Integer> getAllColors();

    @Query("SELECT name FROM users")
    List<String> getAllNames();

    @Query("SELECT u.id AS id, u.name AS name, u.color AS color, u.isOwner AS isOwner, "
            + "u.isStranger AS isStranger, "
            + "u.lastSeenAt AS lastSeenAt, "
            + "(SELECT COUNT(*) FROM user_facts f WHERE f.userId = u.id AND f.done = 0 "
            + "AND f.kind = " + UserFact.KIND_TODO + ") AS pendingTodos, "
            + "(SELECT COUNT(*) FROM user_facts f WHERE f.userId = u.id AND f.done = 0 "
            + "AND f.kind = " + UserFact.KIND_FACT + ") AS pendingFacts "
            + "FROM users u ORDER BY u.isOwner DESC, u.lastSeenAt DESC")
    LiveData<List<UserSummary>> observeSummaries();

    /** 改名后不再是“陌生人N”。 */
    @Query("UPDATE users SET name = :name, isStranger = 0 WHERE id = :id")
    void rename(long id, String name);

    @Query("UPDATE users SET color = :color WHERE id = :id")
    void setColor(long id, int color);

    @Query("UPDATE users SET lastSeenAt = :time WHERE id = :id")
    void touch(long id, long time);

    class UserSummary {
        public long id;
        public String name;
        public int color;
        public boolean isOwner;
        public boolean isStranger;
        public long lastSeenAt;
        /** 未完成的待办数，不含事实。 */
        public int pendingTodos;
        /** 仍成立的事实数。 */
        public int pendingFacts;
    }
}
