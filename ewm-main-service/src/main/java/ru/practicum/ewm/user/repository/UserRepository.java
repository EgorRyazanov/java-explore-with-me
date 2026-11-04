package ru.practicum.ewm.user.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.practicum.ewm.user.model.User;

import java.util.List;

public interface UserRepository extends JpaRepository<User, Long> {

    @Query(value = """
            SELECT id, name, email FROM users
            WHERE (:allUsers = TRUE OR id IN (:ids))
            ORDER BY id
            LIMIT :size OFFSET :offset
            """, nativeQuery = true)
    List<User> findUsers(@Param("ids") List<Long> ids,
                         @Param("allUsers") boolean allUsers,
                         @Param("offset") int offset,
                         @Param("size") int size);

    @Modifying
    @Query("DELETE FROM User u WHERE u.id = :userId")
    int deleteUser(@Param("userId") long userId);
}
