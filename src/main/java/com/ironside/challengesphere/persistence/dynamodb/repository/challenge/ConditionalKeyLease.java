package com.ironside.challengesphere.persistence.dynamodb.repository.challenge;

import com.ironside.challengesphere.persistence.dynamodb.domain.DynamoDomainStore;
import com.ironside.challengesphere.persistence.dynamodb.foundation.DynamoDbItem;
import com.ironside.challengesphere.persistence.dynamodb.foundation.DynamoDbKey;
import com.ironside.challengesphere.persistence.dynamodb.foundation.DynamoDbKeys;
import com.ironside.challengesphere.persistence.dynamodb.foundation.DynamoDbTableGateway;
import com.ironside.challengesphere.persistence.dynamodb.foundation.MainTableGateway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/** Short-lived keyed leases used to preserve former {@code FOR UPDATE} semantics. */
@Component
@ConditionalOnProperty(prefix = "app.persistence", name = "mode", havingValue = "dynamodb")
public class ConditionalKeyLease {

    private static final long LEASE_SECONDS = 120L;
    private static final String LEASE_SK = "LEASE";

    private final MainTableGateway gateway;
    private final DynamoDomainStore store;
    private final Clock clock;
    private final ThreadLocal<Map<DynamoDbKey, HeldLease>> held = ThreadLocal.withInitial(HashMap::new);

    public ConditionalKeyLease(MainTableGateway gateway, DynamoDomainStore store, Clock clock) {
        this.gateway = gateway;
        this.store = store;
        this.clock = clock;
    }

    public void acquire(String scope, String businessKey) {
        DynamoDbKey key = key(scope, businessKey);
        Map<DynamoDbKey, HeldLease> heldLeases = held.get();
        HeldLease existing = heldLeases.get(key);
        if (existing != null) {
            heldLeases.put(key, existing.retain());
            return;
        }

        Instant now = clock.instant();
        String token = store.nextId();
        DynamoDbItem lease = DynamoDbItem.create(key, "CONDITIONAL_LEASE", token, now);
        lease.setExpiresAt(now.plusSeconds(LEASE_SECONDS).getEpochSecond());
        DynamoDbTableGateway.WriteCondition condition = DynamoDbTableGateway.WriteCondition.of(
                "attribute_not_exists(#pk) OR #expires < :now",
                Map.of(
                        "#pk", DynamoDbKeys.PARTITION_KEY,
                        "#expires", DynamoDbKeys.EXPIRES_AT
                ),
                Map.of(":now", AttributeValue.fromN(Long.toString(now.getEpochSecond())))
        );
        try {
            gateway.put(lease, condition);
            heldLeases.put(key, new HeldLease(token, 1));
        } catch (ConditionalCheckFailedException conflict) {
            clearIfEmpty(heldLeases);
            throw new CannotAcquireLockException("Concurrent update in progress for " + scope + ' ' + businessKey);
        } catch (RuntimeException failure) {
            clearIfEmpty(heldLeases);
            throw failure;
        }
    }

    /**
     * Acquires a lease whose ownership is explicit and lexical. Repository
     * methods use this handle when a read/validate/write sequence must retain
     * the same key lease across every exit path.
     */
    public Lease acquireLease(String scope, String businessKey) {
        acquire(scope, businessKey);
        return new Lease(this, scope, businessKey);
    }

    public void release(String scope, String businessKey) {
        DynamoDbKey key = key(scope, businessKey);
        Map<DynamoDbKey, HeldLease> heldLeases = held.get();
        HeldLease lease = heldLeases.get(key);
        if (lease == null) {
            clearIfEmpty(heldLeases);
            return;
        }
        if (lease.depth() > 1) {
            heldLeases.put(key, lease.releaseOne());
            return;
        }
        heldLeases.remove(key);
        try {
            gateway.delete(key, DynamoDbTableGateway.WriteCondition.of(
                    "#payload = :token",
                    Map.of("#payload", DynamoDbKeys.PAYLOAD_JSON),
                    Map.of(":token", AttributeValue.fromS(lease.token()))
            ));
        } catch (ConditionalCheckFailedException ignored) {
            // An expired lease may already have been replaced; never delete its successor.
        } finally {
            clearIfEmpty(heldLeases);
        }
    }

    public boolean isHeld(String scope, String businessKey) {
        Map<DynamoDbKey, HeldLease> heldLeases = held.get();
        boolean result = heldLeases.containsKey(key(scope, businessKey));
        clearIfEmpty(heldLeases);
        return result;
    }

    private static DynamoDbKey key(String scope, String businessKey) {
        return DynamoDbKeys.key(
                ChallengeRepositoryKeys.join(
                        "LOCK", ChallengeRepositoryKeys.require(scope, "scope").toUpperCase(),
                        ChallengeRepositoryKeys.require(businessKey, "businessKey")
                ),
                LEASE_SK
        );
    }

    private void clearIfEmpty(Map<DynamoDbKey, HeldLease> heldLeases) {
        if (heldLeases.isEmpty()) {
            held.remove();
        }
    }

    private record HeldLease(String token, int depth) {
        private HeldLease retain() {
            return new HeldLease(token, depth + 1);
        }

        private HeldLease releaseOne() {
            return new HeldLease(token, depth - 1);
        }
    }

    public static final class Lease implements AutoCloseable {
        private final ConditionalKeyLease owner;
        private final String scope;
        private final String businessKey;
        private boolean closed;

        private Lease(ConditionalKeyLease owner, String scope, String businessKey) {
            this.owner = owner;
            this.scope = scope;
            this.businessKey = businessKey;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            owner.release(scope, businessKey);
        }
    }
}
