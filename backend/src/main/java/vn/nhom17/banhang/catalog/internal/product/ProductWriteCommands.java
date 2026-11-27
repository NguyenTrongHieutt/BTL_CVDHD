package vn.nhom17.banhang.catalog.internal.product;

import java.util.UUID;
import org.springframework.stereotype.Component;
import vn.nhom17.banhang.platform.BackofficeCommandHandler;
import vn.nhom17.banhang.platform.BackofficeCommandResult;

/** Các adapter ở module chủ, không cho platform phụ thuộc catalog.internal. */
final class ProductWriteCommands {
    private ProductWriteCommands() {}
    record Create(UUID key, ProductController.CreateProductRequest request) {}
    record Update(UUID id, ProductController.UpdateProductRequest request) {}
    record Deactivate(UUID id) {}
    record AddVariant(UUID productId, UUID key, ProductVariantController.AddVariantRequest request) {}
    record UpdateVariant(UUID productId, UUID variantId, ProductVariantController.UpdateVariantRequest request) {}
    record DeactivateVariant(UUID productId, UUID variantId) {}

    @Component
    static class CreateHandler implements BackofficeCommandHandler<Create> {
        private final ProductService products;
        CreateHandler(ProductService products) { this.products = products; }
        public String action() { return "PRODUCT_CREATE"; }
        public Class<Create> payloadType() { return Create.class; }
        public BackofficeCommandResult execute(Create payload) {
            var result = products.create(payload.key(), payload.request());
            return BackofficeCommandResult.from(ProductController.createdResponse(result));
        }
    }
    @Component
    static class UpdateHandler implements BackofficeCommandHandler<Update> {
        private final ProductService products;
        UpdateHandler(ProductService products) { this.products = products; }
        public String action() { return "PRODUCT_UPDATE"; }
        public Class<Update> payloadType() { return Update.class; }
        public BackofficeCommandResult execute(Update payload) {
            return BackofficeCommandResult.ok(products.update(payload.id(), payload.request()));
        }
    }
    @Component
    static class DeactivateHandler implements BackofficeCommandHandler<Deactivate> {
        private final ProductService products;
        DeactivateHandler(ProductService products) { this.products = products; }
        public String action() { return "PRODUCT_DEACTIVATE"; }
        public Class<Deactivate> payloadType() { return Deactivate.class; }
        public BackofficeCommandResult execute(Deactivate payload) {
            return BackofficeCommandResult.ok(products.deactivate(payload.id()));
        }
    }
    @Component
    static class AddVariantHandler implements BackofficeCommandHandler<AddVariant> {
        private final ProductVariantService variants;
        AddVariantHandler(ProductVariantService variants) { this.variants = variants; }
        public String action() { return "PRODUCT_VARIANT_ADD"; }
        public Class<AddVariant> payloadType() { return AddVariant.class; }
        public BackofficeCommandResult execute(AddVariant payload) {
            return BackofficeCommandResult.from(ProductVariantController.addedResponse(
                    variants.add(payload.productId(), payload.key(), payload.request())));
        }
    }
    @Component
    static class UpdateVariantHandler implements BackofficeCommandHandler<UpdateVariant> {
        private final ProductVariantService variants;
        UpdateVariantHandler(ProductVariantService variants) { this.variants = variants; }
        public String action() { return "PRODUCT_VARIANT_UPDATE"; }
        public Class<UpdateVariant> payloadType() { return UpdateVariant.class; }
        public BackofficeCommandResult execute(UpdateVariant payload) {
            return BackofficeCommandResult.ok(variants.update(payload.productId(), payload.variantId(), payload.request()));
        }
    }
    @Component
    static class DeactivateVariantHandler implements BackofficeCommandHandler<DeactivateVariant> {
        private final ProductVariantService variants;
        DeactivateVariantHandler(ProductVariantService variants) { this.variants = variants; }
        public String action() { return "PRODUCT_VARIANT_DEACTIVATE"; }
        public Class<DeactivateVariant> payloadType() { return DeactivateVariant.class; }
        public BackofficeCommandResult execute(DeactivateVariant payload) {
            return BackofficeCommandResult.ok(variants.deactivate(payload.productId(), payload.variantId()));
        }
    }
}
