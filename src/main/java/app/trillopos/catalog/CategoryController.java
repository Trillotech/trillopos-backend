package app.trillopos.catalog;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import app.trillopos.shared.web.ApiException;

@RestController
@RequestMapping("/categories")
class CategoryController {

    /** {@code templateKey}: the ready-made category it was made from, if any. */
    record CategoryView(UUID id, String name, UUID parentId, UUID sizeChartId, String templateKey) {

        static CategoryView of(Category c) {
            return new CategoryView(c.getId(), c.getName(), c.getParentId(), c.getSizeChartId(), c.getTemplateKey());
        }
    }

    /**
     * A ready-made category: {@code sizeTemplateKeys} are the library size tables that fit it, the
     * first ({@code sizeTemplateKey}) the one it opens with; {@code sizeKind} is the kind a table
     * the shop makes itself must be. Both empty for goods without sizes.
     */
    record CategoryTemplateView(String key, String name, SizeChartKind sizeKind, String sizeTemplateKey,
            List<String> sizeTemplateKeys) {

        static CategoryTemplateView of(CategoryLibrary.Template t) {
            return new CategoryTemplateView(t.key(), t.name(), t.sizeKind(), t.sizeTemplateKey(),
                    t.sizeTemplateKeys());
        }
    }

    /**
     * The shop's own: {@code name}, optionally {@code parentId} and {@code sizeChartId} (the size
     * chart Add Product opens with). Ready-made: {@code templateKey}, and {@code name} and
     * {@code sizeChartName} in the shop's language if wanted; it is top-level and brings its size
     * table. Taking a ready-made category twice returns the one the shop has.
     */
    record CategoryCreate(@Size(max = 40) String templateKey, @Size(max = 120) String name, UUID parentId,
            UUID sizeChartId, @Size(max = 80) String sizeChartName) {
    }

    /** Every field optional; {@code clearSizeChart} removes the category's size chart. */
    record CategoryUpdate(@Size(min = 1, max = 120) String name, UUID sizeChartId, Boolean clearSizeChart) {
    }

    private final CategoryRepository categories;
    private final SizeChartRepository sizeCharts;
    private final SizeChartService copies;

    CategoryController(CategoryRepository categories, SizeChartRepository sizeCharts, SizeChartService copies) {
        this.categories = categories;
        this.sizeCharts = sizeCharts;
        this.copies = copies;
    }

    @GetMapping
    List<CategoryView> list() {
        return categories.findAllByOrderByNameAsc().stream().map(CategoryView::of).toList();
    }

    @GetMapping("/library")
    List<CategoryTemplateView> library() {
        return CategoryLibrary.all().stream().map(CategoryTemplateView::of).toList();
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('OWNER', 'STOCK_MANAGER')")
    @Transactional
    ResponseEntity<CategoryView> create(@Valid @RequestBody CategoryCreate request) {
        if (request.templateKey() != null) {
            CategoryLibrary.Template template = CategoryLibrary.find(request.templateKey())
                    .orElseThrow(() -> ApiException.badRequest("category_not_found", "no such ready-made category"));
            Optional<Category> existing = categories.findByTemplateKeyAndArchivedAtIsNull(template.key());
            if (existing.isPresent()) {
                return ResponseEntity.ok(CategoryView.of(existing.get()));
            }
            Category category = new Category(blankToNull(request.name()) == null ? template.name()
                    : request.name().strip(), null);
            category.setTemplateKey(template.key());
            if (template.sizeTemplateKey() != null) {
                category.setSizeChartId(copies.copy(template.sizeTemplateKey(), request.sizeChartName()).chart().getId());
            }
            return ResponseEntity.status(HttpStatus.CREATED).body(CategoryView.of(categories.save(category)));
        }
        if (blankToNull(request.name()) == null) {
            throw ApiException.badRequest("validation_failed", "a category needs a name");
        }
        if (request.parentId() != null) {
            Category parent = categories.findById(request.parentId())
                    .orElseThrow(() -> ApiException.badRequest("category_not_found", "no such parent category"));
            if (parent.getParentId() != null) {
                throw ApiException.badRequest("category_too_deep", "categories are kept to two levels");
            }
        }
        Category category = new Category(request.name().strip(), request.parentId());
        category.setSizeChartId(liveChart(request.sizeChartId()));
        return ResponseEntity.status(HttpStatus.CREATED).body(CategoryView.of(categories.save(category)));
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

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
