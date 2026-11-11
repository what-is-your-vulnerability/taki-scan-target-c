package com.bss.customer.consultant;

import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bss.customer.account.Account;
import com.bss.customer.account.AccountMapper;
import com.bss.customer.account.AccountStatus;
import com.bss.customer.audit.AuditRecorder;
import com.bss.customer.common.ApiException;
import com.bss.customer.common.PageParams;
import com.bss.customer.common.PageResponse;
import com.bss.customer.customer.CustomerMapper;
import com.bss.customer.customer.CustomerSearch;
import com.bss.customer.masking.CustomerResponseMapper;
import com.bss.customer.profile.ContactUpdateRequest;
import com.bss.customer.security.LoginAccount;

@Service
public class ConsultantCustomerService {

    private static final String TARGET_CUSTOMER = "CUSTOMER";

    private final AccountMapper accountMapper;
    private final CustomerMapper customerMapper;
    private final CustomerResponseMapper responseMapper;
    private final AuditRecorder auditRecorder;

    public ConsultantCustomerService(AccountMapper accountMapper, CustomerMapper customerMapper,
                                     CustomerResponseMapper responseMapper, AuditRecorder auditRecorder) {
        this.accountMapper = accountMapper;
        this.customerMapper = customerMapper;
        this.responseMapper = responseMapper;
        this.auditRecorder = auditRecorder;
    }

    @Transactional
    public PageResponse<CustomerListItem> list(LoginAccount actor, int page, int size) {
        PageParams.validate(page, size);
        List<CustomerListItem> items = customerMapper.findAssigned(actor.accountId(), page * size, size).stream()
                .map(responseMapper::toConsultantListItem)
                .toList();
        auditRecorder.success(actor, "CUSTOMER_LIST_VIEW", TARGET_CUSTOMER, null, null, null);
        return new PageResponse<>(items, page, customerMapper.countAssigned(actor.accountId()));
    }

    @Transactional
    public PageResponse<CustomerListItem> search(LoginAccount actor, String name, String phone, String sort,
                                                 int page, int size) {
        PageParams.validate(page, size);
        CustomerSort customerSort = CustomerSort.parse(sort);
        String nameValue = name == null || name.isBlank() ? null : name;
        if (nameValue != null && nameValue.length() > 50) {
            throw ApiException.badRequest();
        }
        String phoneDigits = phone == null || phone.isBlank() ? null : phone.replaceAll("\\D", "");
        if (phoneDigits != null && (phoneDigits.isEmpty() || phoneDigits.length() > 20)) {
            throw ApiException.badRequest();
        }
        CustomerSearch search = new CustomerSearch(actor.accountId(), toLikeTerm(nameValue), toLikeTerm(phoneDigits),
                customerSort.field(), customerSort.descending(), page * size, size);
        List<CustomerListItem> items = customerMapper.searchAssigned(search).stream()
                .map(responseMapper::toConsultantListItem)
                .toList();
        auditRecorder.success(actor, "CUSTOMER_SEARCH", TARGET_CUSTOMER, null, null, null);
        return new PageResponse<>(items, page, customerMapper.countSearchAssigned(search));
    }

    @Transactional
    public ConsultantCustomerView getProfile(LoginAccount actor, long customerId) {
        Account member = loadAssignedCustomer(actor, customerId, "CUSTOMER_VIEW");
        auditRecorder.success(actor, "CUSTOMER_VIEW", TARGET_CUSTOMER, String.valueOf(customerId), customerId, null);
        return responseMapper.toConsultantView(member);
    }

    @Transactional
    public ConsultantCustomerView updateContact(LoginAccount actor, long customerId, ContactUpdateRequest request) {
        if (!request.hasChanges()) {
            throw ApiException.badRequest();
        }
        loadAssignedCustomer(actor, customerId, "CUSTOMER_CONTACT_UPDATE");
        customerMapper.updateContact(customerId, request.phone(), request.email(), request.address(), Instant.now());
        auditRecorder.success(actor, "CUSTOMER_CONTACT_UPDATE", TARGET_CUSTOMER, String.valueOf(customerId), customerId, null);
        return responseMapper.toConsultantView(accountMapper.findMemberByCustomerId(customerId));
    }

    private String toLikeTerm(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private Account loadAssignedCustomer(LoginAccount actor, long customerId, String action) {
        Account member = accountMapper.findMemberByCustomerId(customerId);
        if (member == null
                || member.getStatus() == AccountStatus.DELETED
                || !actor.accountId().equals(member.getCustomer().getAssignedConsultantId())) {
            auditRecorder.denied(actor, action, TARGET_CUSTOMER, String.valueOf(customerId), customerId, null);
            throw ApiException.notFound();
        }
        return member;
    }
}
