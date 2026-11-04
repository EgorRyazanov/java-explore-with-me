package ru.practicum.ewm.category.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.practicum.ewm.category.model.Category;

import java.util.List;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    @Query(value = """
            SELECT id, name FROM categories
            ORDER BY id
            LIMIT :size OFFSET :offset
            """, nativeQuery = true)
    List<Category> findCategories(@Param("offset") int offset, @Param("size") int size);

    @Modifying
    @Query("DELETE FROM Category c WHERE c.id = :catId")
    int deleteCategory(@Param("catId") long catId);
}
