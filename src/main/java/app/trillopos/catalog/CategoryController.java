package app.trillopos.catalog;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import app.trillopos.shared.web.ApiException;

@RestController
@RequestMapping("/categories")
class CategoryController {

    record CategoryView(UUID id, String name, UUID parentId, UUID sizeChartId) {

        static CategoryView of(Category c) {
            return new CategoryView(c.getId(), c.getName(), c.getParentId(), c.getSizeChartId());
        }
    }

    /** {@code sizeChartId}: the size chart Add Product opens with for this category. */
    record CategoryCreate(@NotBlank @Size(max = 120) String name, UUID parentId, UUID sizeChartId) {
    }

    /** Every field optional; {@code clearSizeChart} removes the category's size chart. */
    record CategoryUpdate(@Size(min = 1, max = 120) String name, UUID sizeChartId, Boolean clearSizeChart) {
    }

    private final CategoryRepository categories;
    private final SizeChartRepository sizeCharts;

    CategoryController(CategoryRepository categories, SizeChartRepository sizeCharts) {
        this.categories = categories;
        this.sizeCharts = sizeCharts;
    }

    @GetMapping
    List<CategoryView> list() {
        return categories.findAllByOrderByNameAsc().stream().map(CategoryView::of).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('OWNER', 'STOCK_MANAGER')")
    @Transactional
    CategoryView create(@Valid @RequestBody CategoryCreate request) {
        if (request.parentId() != null) {
            Category parent = categories.findById(request.parentId())
                    .orElseThrow(() -> ApiException.badRequest("category_not_found", "no such parent category"));
            if (parent.getParentId() != null) {
                throw ApiException.badRequest("category_too_deep", "categories are kept to two levels");
            }
        }
        Category category = new Category(request.name(), request.parentId());
        category.setSizeChartId(liveChart(request.sizeChartId()));
        return CategoryView.of(categories.save(category));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyRole('OWNER', 'STOCK_MANAGER')")
    @Transactional
    CategoryView update(@PathVariable UUID id, @Valid @RequestBody CategoryUpdate request) {
        Category category = categories.findById(id)
                .orElseThrow(() -> ApiException.notFound("category_not_found", "no such category"));
        if (request.name() != null) {
            category.setName(request.name());
        }
        if (Boolean.TRUE.equals(request.clearSizeChart())) {
            category.setSizeChartId(null);
        } else if (request.sizeChartId() != null) {
            category.setSizeChartId(liveChart(request.sizeChartId()));
        }
        return CategoryView.of(category);
    }

    private UUID liveChart(UUID sizeChartId) {
        if (sizeChartId == null) {
            return null;
        }
        return sizeCharts.findById(sizeChartId).filter(chart -> !chart.isArchived())
                .orElseThrow(() -> ApiException.badRequest("size_chart_not_found", "no such size chart"))
                .getId();
    }
}
