package com.alfagift.finledger.service.impl;

import com.alfagift.finledger.domain.entities.Account;
import com.alfagift.finledger.domain.enums.AccountStatus;
import com.alfagift.finledger.domain.enums.AccountType;
import com.alfagift.finledger.domain.enums.Currency;
import com.alfagift.finledger.dto.AccountCreateRequest;
import com.alfagift.finledger.dto.AccountResponse;
import com.alfagift.finledger.dto.AccountStatusRequest;
import com.alfagift.finledger.dto.BalanceResponse;
import com.alfagift.finledger.dto.PageResponse;
import com.alfagift.finledger.infrastructure.Idempotent;
import com.alfagift.finledger.infrastructure.InsufficientBalanceException;
import com.alfagift.finledger.repository.AccountRepository;
import com.alfagift.finledger.service.AccountService;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Implementasi {@link AccountService}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AccountServiceImpl implements AccountService {

    private final AccountRepository accountRepository;

    @Override
    @Idempotent
    @Transactional
    public AccountResponse createAccount(AccountCreateRequest request, String idempotencyKey) {
        log.info("Membuat akun baru untuk ownerId={}, currency={}", request.getOwnerId(), request.getCurrency());

        // Cek duplikat ownerId + currency
        Optional<Account> existing = accountRepository.findAccountByOwnerIdAndCurrencyCustom(
                request.getOwnerId(), request.getCurrency());
        if (existing.isPresent()) {
            throw new IllegalArgumentException(
                    String.format("Akun untuk ownerId %s currency %s sudah ada", request.getOwnerId(), request.getCurrency()));
        }

        Account account = Account.builder()
                .ownerId(request.getOwnerId())
                .accountName(request.getAccountName())
                .accountType(request.getAccountType())
                .currency(request.getCurrency())
                .balance(request.getInitialBalance() != null ? request.getInitialBalance() : java.math.BigDecimal.ZERO)
                .status(com.alfagift.finledger.domain.enums.AccountStatus.ACTIVE)
                .build();

        Account saved = accountRepository.save(account);
        log.info("Akun berhasil dibuat: id={}, ownerId={}", saved.getId(), saved.getOwnerId());
        return toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public AccountResponse getAccount(UUID accountId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new EntityNotFoundException("Akun tidak ditemukan: " + accountId));
        return toResponse(account);
    }

    @Override
    @Transactional(readOnly = true)
    public BalanceResponse getBalance(UUID accountId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new EntityNotFoundException("Akun tidak ditemukan: " + accountId));

        return BalanceResponse.builder()
                .accountId(account.getId())
                .ownerId(account.getOwnerId())
                .currency(account.getCurrency())
                .balance(account.getBalance())
                .status(account.getStatus())
                .asOf(java.time.LocalDateTime.now())
                .build();
    }

    @Override
    @Idempotent
    @Transactional
    public AccountResponse updateStatus(UUID accountId, AccountStatusRequest request, String idempotencyKey) {
        log.info("Mengubah status akun {} menjadi {}", accountId, request.getStatus());

        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new EntityNotFoundException("Akun tidak ditemukan: " + accountId));

        AccountStatus currentStatus = account.getStatus();
        AccountStatus newStatus = request.getStatus();

        // Validasi transisi legal
        if (!isValidTransition(currentStatus, newStatus)) {
            throw new IllegalArgumentException(
                    String.format("Transisi status %s -> %s tidak diizinkan", currentStatus, newStatus));
        }

        account.setStatus(newStatus);
        Account saved = accountRepository.save(account);
        log.info("Status akun {} berubah: {} -> {}", accountId, currentStatus, newStatus);
        return toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<AccountResponse> searchAccounts(
            String ownerId,
            String currency,
            String accountType,
            String status,
            int page,
            int size,
            String sort) {

        // Validate page size
        if (size > 100) size = 100;
        if (page < 0) page = 0;

        Pageable pageable = buildPageable(page, size, sort);

        Specification<Account> spec = buildSpecification(ownerId, currency, accountType, status);
        Page<Account> accountPage = accountRepository.findAll(spec, pageable);

        List<AccountResponse> content = accountPage.getContent().stream()
                .map(this::toResponse)
                .toList();

        return PageResponse.<AccountResponse>builder()
                .content(content)
                .page(PageResponse.PageMetadata.builder()
                        .number(accountPage.getNumber())
                        .size(accountPage.getSize())
                        .totalElements(accountPage.getTotalElements())
                        .totalPages(accountPage.getTotalPages())
                        .first(accountPage.isFirst())
                        .last(accountPage.isLast())
                        .numberOfElements(accountPage.getNumberOfElements())
                        .build())
                .sort(buildSortInfo(accountPage.getSort()))
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public void validateSufficientBalance(UUID accountId, BigDecimal requiredAmount) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new EntityNotFoundException("Akun tidak ditemukan: " + accountId));

        if (account.getBalance().compareTo(requiredAmount) < 0) {
            throw new InsufficientBalanceException(
                    String.format("Saldo tidak mencukupi: tersedia %s, butuh %s", account.getBalance(), requiredAmount));
        }
    }

    // ===== Private helpers =====

    private boolean isValidTransition(AccountStatus from, AccountStatus to) {
        if (from == to) return true;

        return switch (from) {
            case ACTIVE -> to == AccountStatus.SUSPENDED || to == AccountStatus.CLOSED;
            case SUSPENDED -> to == AccountStatus.ACTIVE || to == AccountStatus.CLOSED;
            case CLOSED -> to == AccountStatus.ACTIVE; // reopen (admin only)
        };
    }

    private AccountResponse toResponse(Account account) {
        return AccountResponse.builder()
                .id(account.getId())
                .ownerId(account.getOwnerId())
                .accountName(account.getAccountName())
                .accountType(account.getAccountType())
                .currency(account.getCurrency())
                .balance(account.getBalance())
                .status(account.getStatus())
                .version(account.getVersion())
                .createdAt(account.getCreatedAt())
                .updatedAt(account.getUpdatedAt())
                .build();
    }

    private Specification<Account> buildSpecification(
            String ownerId, String currency, String accountType, String status) {

        return (root, query, cb) -> {
            List<jakarta.persistence.criteria.Predicate> predicates = new java.util.ArrayList<>();

            if (ownerId != null && !ownerId.isBlank()) {
                predicates.add(cb.equal(root.get("ownerId"), ownerId));
            }
            if (currency != null && !currency.isBlank()) {
                try {
                    Currency curr = Currency.valueOf(currency);
                    predicates.add(cb.equal(root.get("currency"), curr));
                } catch (IllegalArgumentException ignored) {
                    // invalid enum - ignore filter
                }
            }
            if (accountType != null && !accountType.isBlank()) {
                try {
                    AccountType at = AccountType.valueOf(accountType);
                    predicates.add(cb.equal(root.get("accountType"), at));
                } catch (IllegalArgumentException ignored) {
                }
            }
            if (status != null && !status.isBlank()) {
                try {
                    AccountStatus st = AccountStatus.valueOf(status);
                    predicates.add(cb.equal(root.get("status"), st));
                } catch (IllegalArgumentException ignored) {
                }
            }

            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };
    }

    private Pageable buildPageable(int page, int size, String sort) {
        if (sort != null && !sort.isBlank()) {
            String[] parts = sort.split(",");
            String field = parts[0];
            Sort.Direction direction = parts.length > 1 && "desc".equalsIgnoreCase(parts[1])
                    ? Sort.Direction.DESC : Sort.Direction.ASC;

            // Whitelist sortable fields
            if (List.of("createdAt", "updatedAt", "balance", "accountName", "ownerId", "currency", "status").contains(field)) {
                return PageRequest.of(page, size, Sort.by(direction, field));
            }
        }
        return PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
    }

    private List<PageResponse.SortInfo> buildSortInfo(Sort sort) {
        return sort.stream()
                .map(o -> PageResponse.SortInfo.builder()
                        .field(o.getProperty())
                        .direction(o.getDirection().name())
                        .build())
                .toList();
    }
}