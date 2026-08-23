package com.ironside.challengesphere.repository;

import com.ironside.challengesphere.model.AuthIdentity;
import com.ironside.challengesphere.persistence.dynamodb.domain.DynamoDomainStore;
import com.ironside.challengesphere.persistence.dynamodb.domain.DynamoProjection;
import com.ironside.challengesphere.persistence.dynamodb.repository.common.AbstractDynamoRepository;
import com.ironside.challengesphere.persistence.dynamodb.repository.user.TransactionalDynamoWriter;
import com.ironside.challengesphere.persistence.dynamodb.repository.user.UserDynamoKeys;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public class AuthIdentityRepository extends AbstractDynamoRepository<AuthIdentity> {

    private static final String ENTITY_TYPE = "AUTH_IDENTITY";

    private final TransactionalDynamoWriter writer;

    public AuthIdentityRepository(DynamoDomainStore store, TransactionalDynamoWriter writer) {
        super(store, ENTITY_TYPE, AuthIdentity.class);
        this.writer = writer;
    }

    @Override
    public AuthIdentity save(AuthIdentity identity) {
        if (identity == null) {
            throw new IllegalArgumentException("Auth identity must not be null");
        }
        boolean creating = identity.getId() == null || identity.getId().isBlank();
        if (creating) {
            identity.setId(store.nextId());
        }
        Instant now = Instant.now();
        if (identity.getCreatedAt() == null) {
            identity.setCreatedAt(now);
        }
        if (identity.getLastUsedAt() == null) {
            identity.setLastUsedAt(now);
        }
        requireIdentity(identity);

        Optional<TransactionalDynamoWriter.Versioned<AuthIdentity>> previous =
                writer.loadVersioned(ENTITY_TYPE, identity.getId(), AuthIdentity.class);
        writer.save(
                ENTITY_TYPE,
                identity.getId(),
                identity,
                allProjections(identity),
                previous.map(TransactionalDynamoWriter.Versioned::value)
                        .map(this::allProjections).orElseGet(List::of),
                uniqueProjections(identity),
                previous.map(TransactionalDynamoWriter.Versioned::value)
                        .map(this::uniqueProjections).orElseGet(List::of),
                creating,
                previous.map(TransactionalDynamoWriter.Versioned::version).orElse(null)
        );
        return identity;
    }

    @Override
    public void delete(AuthIdentity identity) {
        if (identity == null || identity.getId() == null || identity.getId().isBlank()) {
            return;
        }
        writer.loadVersioned(ENTITY_TYPE, identity.getId(), AuthIdentity.class)
                .ifPresent(current -> writer.delete(
                        ENTITY_TYPE,
                        identity.getId(),
                        allProjections(current.value()),
                        uniqueProjections(current.value()),
                        current.version()
                ));
    }

    public Optional<AuthIdentity> findByProviderAndSubject(String provider, String subject) {
        if (provider == null || provider.isBlank() || subject == null || subject.isBlank()) {
            return Optional.empty();
        }
        return uniqueLookup("PROVIDER_SUBJECT", normalizedProvider(provider) + '\u0000' + subject.strip());
    }

    public Optional<AuthIdentity> findByUserIdAndProvider(String userId, String provider) {
        if (userId == null || userId.isBlank() || provider == null || provider.isBlank()) {
            return Optional.empty();
        }
        return uniqueLookup("USER_PROVIDER", userId + '\u0000' + normalizedProvider(provider));
    }

    public List<AuthIdentity> findByUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            return List.of();
        }
        return store.queryAll(
                UserDynamoKeys.ownerCollectionPk("AUTH_IDENTITIES", userId),
                "PROVIDER#",
                UserDynamoKeys.MAX_QUERY_ITEMS,
                true,
                AuthIdentity.class,
                true
        );
    }

    @Override
    protected String id(AuthIdentity entity) {
        return entity.getId();
    }

    @Override
    protected void assignId(AuthIdentity entity, String id) {
        entity.setId(id);
    }

    @Override
    protected Collection<DynamoProjection> projections(AuthIdentity identity) {
        if (identity.getUser() == null || identity.getUser().getId() == null) {
            return List.of();
        }
        return List.of(UserDynamoKeys.projection(
                UserDynamoKeys.ownerCollectionPk("AUTH_IDENTITIES", identity.getUser().getId()),
                "PROVIDER#" + normalizedProvider(identity.getProvider()) + '#' + identity.getId()
        ));
    }

    private List<DynamoProjection> uniqueProjections(AuthIdentity identity) {
        requireIdentity(identity);
        String provider = normalizedProvider(identity.getProvider());
        String userId = identity.getUser().getId();
        return List.of(
                UserDynamoKeys.projection(
                        UserDynamoKeys.uniquePkExact(
                                ENTITY_TYPE,
                                "PROVIDER_SUBJECT",
                                provider + '\u0000' + identity.getSubject().strip()
                        ),
                        UserDynamoKeys.LOOKUP
                ),
                UserDynamoKeys.projection(
                        UserDynamoKeys.uniquePk(
                                ENTITY_TYPE,
                                "USER_PROVIDER",
                                userId + '\u0000' + provider
                        ),
                        UserDynamoKeys.LOOKUP
                )
        );
    }

    private Optional<AuthIdentity> uniqueLookup(String field, String value) {
        return store.queryAll(
                        "PROVIDER_SUBJECT".equals(field)
                                ? UserDynamoKeys.uniquePkExact(ENTITY_TYPE, field, value)
                                : UserDynamoKeys.uniquePk(ENTITY_TYPE, field, value),
                        UserDynamoKeys.LOOKUP,
                        1,
                        true,
                        AuthIdentity.class,
                        true
                ).stream()
                .findFirst();
    }

    private void requireIdentity(AuthIdentity identity) {
        if (identity.getUser() == null || identity.getUser().getId() == null
                || identity.getUser().getId().isBlank()) {
            throw new IllegalArgumentException("Auth identity user must not be null");
        }
        requireText(identity.getProvider(), "provider");
        requireText(identity.getSubject(), "subject");
    }

    private String normalizedProvider(String provider) {
        return UserDynamoKeys.normalize(provider).toUpperCase();
    }
}
