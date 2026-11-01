package ru.practicum.ewm.category.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.practicum.ewm.category.dto.CategoryDto;
import ru.practicum.ewm.category.dto.NewCategoryDto;
import ru.practicum.ewm.category.mapper.CategoryMapper;
import ru.practicum.ewm.category.model.Category;
import ru.practicum.ewm.category.repository.CategoryRepository;
import ru.practicum.ewm.exception.NotFoundException;

import java.util.List;

@Service
@Transactional(readOnly = true)
public class CategoryService {

    private final CategoryRepository categoryRepository;
    private final CategoryMapper categoryMapper;

    public CategoryService(CategoryRepository categoryRepository, CategoryMapper categoryMapper) {
        this.categoryRepository = categoryRepository;
        this.categoryMapper = categoryMapper;
    }

    @Transactional
    public CategoryDto createCategory(NewCategoryDto request) {
        return categoryMapper.toDto(categoryRepository.save(new Category(request.name())));
    }

    public List<CategoryDto> getCategories(int from, int size) {
        return categoryRepository.findCategories(from, size).stream().map(categoryMapper::toDto).toList();
    }

    public CategoryDto getCategory(long catId) {
        return categoryMapper.toDto(findCategory(catId));
    }

    @Transactional
    public CategoryDto updateCategory(long catId, NewCategoryDto request) {
        Category category = findCategory(catId);
        category.rename(request.name());
        return categoryMapper.toDto(category);
    }

    @Transactional
    public void deleteCategory(long catId) {
        if (categoryRepository.deleteCategory(catId) == 0) {
            throw new NotFoundException("Category with id=" + catId + " was not found");
        }
    }

    private Category findCategory(long catId) {
        return categoryRepository.findById(catId)
                .orElseThrow(() -> new NotFoundException("Category with id=" + catId + " was not found"));
    }
}
