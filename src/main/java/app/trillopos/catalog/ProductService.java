package app.trillopos.catalog;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import app.trillopos.inventory.StockBalance;
import app.trillopos.inventory.StockBalanceRepository;
import app.trillopos.inventory.StockDocumentService;
import app.trillopos.inventory.StockDocumentService.DocumentCommand;
import app.trillopos.inventory.StockDocumentService.LineCommand;
import app.trillopos.inventory.StockDocumentType;
import app.trillopos.inventory.StockMovementReason;
import app.trillopos.inventory.StockDocumentService.OpeningStock;
import app.trillopos.org.BusinessType;
import app.trillopos.org.LocationRepository;
import app.trillopos.org.OrganizationRepository;
import app.trillopos.shared.tenant.TenantContext;
import app.trillopos.shared.web.ApiException;

@Service
public class ProductService {

    /** {@code sizeEquivalents}: the same size in other systems; an empty one removes it. */
    public record ProductCommand(UUID id, String sku, String name, UUID categoryId, UUID defaultSupplierId,
            ProductUnit unit, String sizeLabel, String productGroupKey, BigDecimal retailPrice,
            BigDecimal wholesalePrice, Boolean taxable, Boolean trackInventory, Integer reorderPoint,
            Boolean sellInPos, Boolean sellOnline, Boolean active, List<String> barcodes,
            List<OpeningStock> openingStock, String sizeEquivalents) {

        /** A product without size equivalents. */
        public ProductCommand(UUID id, String sku, String name, UUID categoryId, UUID defaultSupplierId,
                ProductUnit unit, String sizeLabel, String productGroupKey, BigDecimal retailPrice,
                BigDecimal wholesalePrice, Boolean taxable, Boolean trackInventory, Integer reorderPoint,
                Boolean sellInPos, Boolean sellOnline, Boolean active, List<String> barcodes,
                List<OpeningStock> openingStock) {
            this(id, sku, name, categoryId, defaultSupplierId, unit, sizeLabel, productGroupKey, retailPrice,
                    wholesalePrice, taxable, trackInventory, reorderPoint, sellInPos, sellOnline, active, barcodes,
                    openingStock, null);
        }
    }

    /**
     * One model in several sizes of one chart: {@code name} is the model ("Converse Chuck 70 Black"),
     * each size becomes its own product. Opening stock, when a size has any, goes to {@code locationId}
     * at {@code unitCost}.
     */
    public record SizesCommand(String name, UUID categoryId, UUID defaultSupplierId, ProductUnit unit,
            BigDecimal retailPrice, BigDecimal wholesalePrice, Boolean taxable, Boolean trackInventory,
            Integer reorderPoint, Boolean sellInPos, Boolean sellOnline, Boolean active, UUID sizeChartId,
            UUID locationId, BigDecimal unitCost, List<SizeCommand> sizes) {
    }

    public record SizeCommand(String label, BigDecimal quantity) {
    }

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String SKU_ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";

    private final ProductRepository products;
    private final ProductBarcodeRepository barcodes;
    private final CategoryRepository categories;
    private final SupplierRepository suppliers;
    private final LocationRepository locations;
    private final LocationProductRepository locationProducts;
    private final StockDocumentService stockDocuments;
    private final StockBalanceRepository balances;
    private final OrganizationRepository organizations;
    private final SizeChartRepository sizeCharts;
    private final Clock clock;

    public ProductService(ProductRepository products, ProductBarcodeRepository barcodes,
            CategoryRepository categories, SupplierRepository suppliers, LocationRepository locations,
            LocationProductRepository locationProducts, StockDocumentService stockDocuments,
            StockBalanceRepository balances, OrganizationRepository organizations, SizeChartRepository sizeCharts,
            Clock clock) {
        this.products = products;
        this.barcodes = barcodes;
        this.categories = categories;
        this.suppliers = suppliers;
        this.locations = locations;
        this.locationProducts = locationProducts;
        this.stockDocuments = stockDocuments;
        this.balances = balances;
        this.organizations = organizations;
        this.sizeCharts = sizeCharts;
        this.clock = clock;
    }

    @Transactional
    public Product create(ProductCommand command) {
        if (command.name() == null || command.name().isBlank() || command.unit() == null
                || command.retailPrice() == null) {
            throw ApiException.badRequest("validation_failed", "name, unit and retailPrice are required");
        }
        if (command.id() != null && products.existsById(command.id())) {
            throw ApiException.conflict("product_exists", "a product with that id already exists");
        }
        Category category = category(command.categoryId());
        String sku = command.sku() != null && !command.sku().isBlank()
                ? command.sku().trim()
                : generateSku(category);
        if (products.existsBySku(sku)) {
            throw ApiException.conflict("sku_taken", "SKU " + sku + " is already used (archived SKUs stay reserved)");
        }
        Product product = new Product(sku, command.name(), command.unit(), command.retailPrice());
        if (command.id() != null) {
            product.withId(command.id());
        }
        // an online shop sells what it lists online unless told otherwise
        if (command.sellOnline() == null && organizations.findById(TenantContext.requireOrganizationId())
                .map(o -> o.getBusinessType() == BusinessType.ONLINE).orElse(false)) {
            product.setSellOnline(true);
        }
        apply(product, command);
        product = products.save(product);
        if (command.barcodes() != null) {
            for (String code : command.barcodes()) {
                addBarcode(product.getId(), code);
            }
        }
        if (command.openingStock() != null && !command.openingStock().isEmpty()) {
            // the Add Product form's opening stock by location: one auto-posted OPENING per location
            stockDocuments.postOpeningStock(product.getId(), command.openingStock());
        }
        return product;
    }

    /**
     * Every size of one model at once, all or nothing, in the chart's order. Each size is its own
     * product (spec §4, flat SKUs): "Converse Chuck 70 Black · EU 42", SKU "FOO-7KQ2MX-42", all
     * sharing the product group key "FOO-7KQ2MX", each with its size in the chart's other systems
     * ("UK 8 · US M 9 · US W 10.5 · CM 26.5"). Opening stock for the sizes that have some is one
     * OPENING document with a line per size.
     */
    @Transactional
    public List<Product> createSizes(SizesCommand command) {
        if (command.name() == null || command.name().isBlank() || command.unit() == null
                || command.retailPrice() == null) {
            throw ApiException.badRequest("validation_failed", "name, unit and retailPrice are required");
        }
        SizeChart chart = (command.sizeChartId() == null ? Optional.<SizeChart>empty()
                : sizeCharts.findById(command.sizeChartId())).filter(c -> !c.isArchived())
                .orElseThrow(() -> ApiException.badRequest("size_chart_not_found", "no such size chart"));
        List<SizeCommand> requested = command.sizes() == null ? List.of() : command.sizes();
        if (requested.isEmpty()) {
            throw ApiException.badRequest("sizes_required", "pick at least one size");
        }
        Map<String, BigDecimal> quantities = new HashMap<>();
        for (SizeCommand size : requested) {
            List<String> row = chart.row(size.label());
            if (row == null) {
                throw ApiException.badRequest("size_not_in_chart", size.label() + " is not a size in " + chart.getName());
            }
            String label = row.get(0);
            if (quantities.containsKey(label)) {
                throw ApiException.badRequest("duplicate_size", label + " is listed twice");
            }
            BigDecimal quantity = size.quantity();
            if (quantity != null && quantity.signum() < 0) {
                throw ApiException.badRequest("invalid_quantity", "opening stock is zero or more");
            }
            quantities.put(label, quantity == null || quantity.signum() == 0 ? null : quantity);
        }
        if (quantities.values().stream().anyMatch(Objects::nonNull) && command.locationId() == null) {
            throw ApiException.badRequest("location_required", "say where the opening stock is");
        }

        String model = command.name().strip();
        String groupKey = generateGroupKey(category(command.categoryId()));
        List<Product> created = new ArrayList<>();
        List<LineCommand> opening = new ArrayList<>();
        List<List<String>> rows = chart.rows();
        for (int index = 0; index < rows.size(); index++) {
            List<String> row = rows.get(index);
            String label = row.get(0);
            if (!quantities.containsKey(label)) {
                continue;
            }
            String sku = groupKey + "-" + skuPart(label, index);
            Product product = create(new ProductCommand(null, products.existsBySku(sku) ? null : sku,
                    model + " · " + chart.display(label), command.categoryId(), command.defaultSupplierId(),
                    command.unit(), label, groupKey, command.retailPrice(), command.wholesalePrice(),
                    command.taxable(), command.trackInventory(), command.reorderPoint(), command.sellInPos(),
                    command.sellOnline(), command.active(), null, null, chart.equivalents(row)));
            product.setSizeChartId(chart.getId());
            created.add(product);
            if (quantities.get(label) != null) {
                opening.add(new LineCommand(product.getId(), quantities.get(label), command.unitCost(), null));
            }
        }
        if (!opening.isEmpty()) {
            stockDocuments.createAndPost(new DocumentCommand(StockDocumentType.OPENING, command.locationId(), null,
                    null, null, "Opening stock", opening));
        }
        return created;
    }

    @Transactional
    public Product update(UUID id, ProductCommand command) {
        Product product = find(id);
        if (command.sku() != null && !command.sku().isBlank() && !command.sku().trim().equals(product.getSku())) {
            if (products.existsBySku(command.sku().trim())) {
                throw ApiException.conflict("sku_taken", "SKU " + command.sku() + " is already used");
            }
            product.setSku(command.sku().trim());
        }
        if (command.name() != null && !command.name().isBlank()) {
            product.setName(command.name());
        }
        if (command.unit() != null) {
            product.setUnit(command.unit());
        }
        if (command.retailPrice() != null) {
            product.setRetailPrice(command.retailPrice());
        }
        apply(product, command);
        return product;
    }

    /**
     * Deleting a product archives it: it leaves the catalog and the sale screen, while past sales,
     * stock history and reports keep it. An archived product takes no more stock documents, so stock
     * it still held would stay in the stock value for good. With {@code writeOffStock} that stock is
     * posted out first (a STOCK_OUT per location, in this transaction); without it the delete is refused.
     */
    @Transactional
    public void archive(UUID id, boolean writeOffStock) {
        Product product = find(id);
        List<StockBalance> held = balances.findAllByProductIdOrderByLocationId(id).stream()
                .filter(balance -> balance.getQuantity().signum() != 0)
                .toList();
        if (held.stream().anyMatch(balance -> balance.getQuantity().signum() < 0)) {
            throw ApiException.conflict("product_stock_negative",
                    product.getName() + " has negative stock; correct the count before deleting it");
        }
        if (!held.isEmpty() && !writeOffStock) {
            BigDecimal quantity = held.stream().map(StockBalance::getQuantity).reduce(BigDecimal.ZERO, BigDecimal::add);
            throw ApiException.conflict("product_has_stock",
                    product.getName() + " still has " + quantity.stripTrailingZeros().toPlainString() + " in stock");
        }
        for (StockBalance balance : held) {
            stockDocuments.createAndPost(new DocumentCommand(StockDocumentType.STOCK_OUT, balance.getLocationId(),
                    null, null, null, "Written off: product deleted",
                    List.of(new LineCommand(id, balance.getQuantity(), null, StockMovementReason.COUNT_CORRECTION))));
        }
        product.archive(clock.instant());
    }

    @Transactional
    public ProductBarcode addBarcode(UUID productId, String code) {
        find(productId);
        String barcode = code == null ? "" : code.trim();
        if (barcode.isEmpty() || barcode.length() > 64) {
            throw ApiException.badRequest("invalid_barcode", "a barcode is 1–64 characters");
        }
        if (barcodes.findByBarcode(barcode).isPresent()) {
            throw ApiException.conflict("barcode_taken", "barcode " + barcode + " is already assigned");
        }
        return barcodes.save(new ProductBarcode(productId, barcode));
    }

    @Transactional
    public LocationProduct setLocationSettings(UUID productId, UUID locationId, Integer reorderPoint,
            String shelfLocation) {
        find(productId);
        if (locations.findById(locationId).isEmpty()) {
            throw ApiException.notFound("location_not_found", "no such location");
        }
        LocationProduct settings = locationProducts.findByLocationIdAndProductId(locationId, productId)
                .orElseGet(() -> locationProducts.save(new LocationProduct(locationId, productId)));
        settings.setReorderPoint(reorderPoint);
        settings.setShelfLocation(shelfLocation);
        return settings;
    }

    public Product find(UUID id) {
        return products.findById(id)
                .orElseThrow(() -> ApiException.notFound("product_not_found", "no such product"));
    }

    private void apply(Product product, ProductCommand command) {
        if (command.categoryId() != null) {
            product.setCategoryId(category(command.categoryId()).getId());
        }
        if (command.defaultSupplierId() != null) {
            suppliers.findById(command.defaultSupplierId())
                    .orElseThrow(() -> ApiException.badRequest("supplier_not_found", "no such supplier"));
            product.setDefaultSupplierId(command.defaultSupplierId());
        }
        if (command.sizeLabel() != null) {
            product.setSizeLabel(command.sizeLabel());
        }
        if (command.productGroupKey() != null) {
            product.setProductGroupKey(command.productGroupKey());
        }
        if (command.sizeEquivalents() != null) {
            product.setSizeEquivalents(command.sizeEquivalents().isBlank() ? null : command.sizeEquivalents().strip());
        }
        if (command.wholesalePrice() != null) {
            product.setWholesalePrice(command.wholesalePrice());
        }
        if (command.taxable() != null) {
            product.setTaxable(command.taxable());
        }
        if (command.trackInventory() != null) {
            product.setTrackInventory(command.trackInventory());
        }
        if (command.reorderPoint() != null) {
            product.setReorderPoint(command.reorderPoint());
        }
        if (command.sellInPos() != null) {
            product.setSellInPos(command.sellInPos());
        }
        if (command.sellOnline() != null) {
            product.setSellOnline(command.sellOnline());
        }
        if (command.active() != null) {
            product.setActive(command.active());
        }
    }

    private Category category(UUID categoryId) {
        if (categoryId == null) {
            return null;
        }
        return categories.findById(categoryId)
                .orElseThrow(() -> ApiException.badRequest("category_not_found", "no such category"));
    }

    /** "Auto-generated from the category, editable" (spec §4): a category prefix plus random characters. */
    private String generateSku(Category category) {
        String prefix = category == null ? "SKU"
                : category.getName().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        if (prefix.isEmpty()) {
            prefix = "SKU";
        }
        prefix = prefix.substring(0, Math.min(3, prefix.length()));
        for (int attempt = 0; attempt < 5; attempt++) {
            StringBuilder sku = new StringBuilder(prefix).append('-');
            for (int i = 0; i < 6; i++) {
                sku.append(SKU_ALPHABET.charAt(RANDOM.nextInt(SKU_ALPHABET.length())));
            }
            if (!products.existsBySku(sku.toString())) {
                return sku.toString();
            }
        }
        throw ApiException.conflict("sku_unavailable", "could not generate a unique SKU; supply one");
    }

    /** A generated SKU no product has as its SKU or its group key: the stem of a model's size SKUs. */
    private String generateGroupKey(Category category) {
        for (int attempt = 0; attempt < 5; attempt++) {
            String key = generateSku(category);
            if (!products.existsByProductGroupKey(key)) {
                return key;
            }
        }
        throw ApiException.conflict("sku_unavailable", "could not generate a unique SKU; supply one");
    }

    /** "38", "10.5C", "0-3M", "FREESIZE"; the size's position when nothing Latin is left (a Burmese label). */
    private static String skuPart(String label, int index) {
        String part = label.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9.-]", "");
        return part.isEmpty() ? String.valueOf(index + 1) : part;
    }
}
