package vn.nhom17.banhang.catalog.internal.customer;

import java.net.URI;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import vn.nhom17.banhang.platform.BackofficeCommandHandler;
import vn.nhom17.banhang.platform.BackofficeCommandResult;
import vn.nhom17.banhang.platform.Role;

final class CustomerWriteCommands {
    private CustomerWriteCommands() {}
    record Update(UUID id, CustomerController.CustomerRequest request) {}

    @Component
    static class CreateHandler implements BackofficeCommandHandler<CustomerController.CustomerRequest> {
        private final CustomerService customers;
        CreateHandler(CustomerService customers) { this.customers = customers; }
        public String action() { return "CUSTOMER_CREATE"; }
        public Class<CustomerController.CustomerRequest> payloadType() { return CustomerController.CustomerRequest.class; }
        public Set<Role> allowedRoles() { return Set.of(Role.OWNER, Role.STAFF); }
        public BackofficeCommandResult execute(CustomerController.CustomerRequest payload) {
            var created = customers.create(payload);
            return BackofficeCommandResult.from(ResponseEntity.created(URI.create(CustomerController.PATH + "/"
                    + created.customerId())).body(created));
        }
    }
    @Component
    static class UpdateHandler implements BackofficeCommandHandler<Update> {
        private final CustomerService customers;
        UpdateHandler(CustomerService customers) { this.customers = customers; }
        public String action() { return "CUSTOMER_UPDATE"; }
        public Class<Update> payloadType() { return Update.class; }
        public Set<Role> allowedRoles() { return Set.of(Role.OWNER, Role.STAFF); }
        public BackofficeCommandResult execute(Update payload) {
            return BackofficeCommandResult.ok(customers.update(payload.id(), payload.request()));
        }
    }
}
