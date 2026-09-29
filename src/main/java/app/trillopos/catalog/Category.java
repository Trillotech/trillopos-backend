package app.trillopos.catalog;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import app.trillopos.shared.persistence.TenantEntity;

/** Self-referencing for sub-categories; the UI keeps it to two levels. */
@Entity
@Table(name = "category")
public class Category extends TenantEntity {

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "parent_id")
    private UUID parentId;

    /** The size chart Add Product opens with for this category; null for goods without sizes. */
    @Column(name = "size_chart_id")
    private UUID sizeChartId;

    /** The ready-made category ({@link CategoryLibrary}) this one was made from; null for the shop's own. */
    @Column(name = "template_key", length = 40)
    private String templateKey;

    protected Category() {
    }

    public Category(String name, UUID parentId) {
        this.name = name;
        this.parentId = parentId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public UUID getParentId() {
        return parentId;
    }

    public void setParentId(UUID parentId) {
        this.parentId = parentId;
    }

    public UUID getSizeChartId() {
        return sizeChartId;
    }

    public void setSizeChartId(UUID sizeChartId) {
        this.sizeChartId = sizeChartId;
    }

    public String getTemplateKey() {
        return templateKey;
    }

    public void setTemplateKey(String templateKey) {
        this.templateKey = templateKey;
    }
}
