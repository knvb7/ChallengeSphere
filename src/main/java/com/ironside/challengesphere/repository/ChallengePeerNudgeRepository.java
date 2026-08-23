package com.ironside.challengesphere.repository;

import com.ironside.challengesphere.model.ChallengePeerNudge;
import com.ironside.challengesphere.persistence.dynamodb.domain.DynamoDomainStore;
import com.ironside.challengesphere.persistence.dynamodb.domain.DynamoProjection;
import com.ironside.challengesphere.persistence.dynamodb.repository.challenge.AbstractChallengeDynamoRepository;
import com.ironside.challengesphere.persistence.dynamodb.repository.challenge.ChallengeRepositoryKeys;
import com.ironside.challengesphere.persistence.dynamodb.repository.challenge.ChallengeTransactionalWriter;
import com.ironside.challengesphere.persistence.dynamodb.repository.challenge.ConditionalKeyLease;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;

@Repository
@ConditionalOnProperty(prefix = "app.persistence", name = "mode", havingValue = "dynamodb")
public class ChallengePeerNudgeRepository
        extends AbstractChallengeDynamoRepository<ChallengePeerNudge> {

    private final Clock clock;

    public ChallengePeerNudgeRepository(
            DynamoDomainStore store,
            ChallengeTransactionalWriter writer,
            ConditionalKeyLease leases,
            Clock clock
    ) {
        super(store, writer, leases, "CHALLENGE_PEER_NUDGE", ChallengePeerNudge.class);
        this.clock = clock;
    }

    public List<ChallengePeerNudge> findBySenderUserId(String userId) {
        return queryProjectionPartition(
                ChallengeRepositoryKeys.peerNudgeSenderPk(userId),
                ChallengeRepositoryKeys.CREATED_PREFIX,
                false
        );
    }

    public List<ChallengePeerNudge> findByRecipientUserId(String userId) {
        return queryProjectionPartition(
                ChallengeRepositoryKeys.peerNudgeRecipientPk(userId),
                ChallengeRepositoryKeys.CREATED_PREFIX,
                false
        );
    }

    public List<ChallengePeerNudge> findByParticipantUserId(String userId) {
        LinkedHashMap<String, ChallengePeerNudge> distinct = new LinkedHashMap<>();
        findBySenderUserId(userId).forEach(nudge -> distinct.put(nudge.getId(), nudge));
        findByRecipientUserId(userId).forEach(nudge -> distinct.put(nudge.getId(), nudge));
        return List.copyOf(distinct.values());
    }

    @Override
    protected void prepareForSave(ChallengePeerNudge nudge, boolean creating) {
        if (nudge.getSentAt() == null) {
            nudge.setSentAt(Timestamp.from(clock.instant()));
        }
    }

    @Override
    protected String id(ChallengePeerNudge nudge) {
        return nudge.getId();
    }

    @Override
    protected void assignId(ChallengePeerNudge nudge, String id) {
        nudge.setId(id);
    }

    @Override
    protected Collection<DynamoProjection> projections(ChallengePeerNudge nudge) {
        String id = ChallengeRepositoryKeys.require(nudge.getId(), "nudge.id");
        String challengeId = ChallengeRepositoryKeys.require(
                nudge.getChallengeId(), "nudge.challengeId"
        );
        String senderId = ChallengeRepositoryKeys.require(
                nudge.getSenderUserId(), "nudge.senderUserId"
        );
        String recipientId = ChallengeRepositoryKeys.require(
                nudge.getRecipientUserId(), "nudge.recipientUserId"
        );
        String createdSk = ChallengeRepositoryKeys.createdSk(nudge.getSentAt(), id);
        return List.of(
                DynamoProjection.of(
                        ChallengeRepositoryKeys.peerNudgeChallengePk(challengeId), createdSk
                ),
                DynamoProjection.of(
                        ChallengeRepositoryKeys.peerNudgeSenderPk(senderId), createdSk
                ),
                DynamoProjection.of(
                        ChallengeRepositoryKeys.peerNudgeRecipientPk(recipientId), createdSk
                )
        );
    }
}
