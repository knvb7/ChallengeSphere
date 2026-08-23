package com.ironside.challengesphere.repository;

import com.ironside.challengesphere.model.ChallengeCrewInvite;
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

@Repository
@ConditionalOnProperty(prefix = "app.persistence", name = "mode", havingValue = "dynamodb")
public class ChallengeCrewInviteRepository
        extends AbstractChallengeDynamoRepository<ChallengeCrewInvite> {

    private static final Comparator<ChallengeCrewInvite> RECENT_FIRST = Comparator
            .comparing(ChallengeCrewInvite::getCreatedAt, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(ChallengeCrewInvite::getId, Comparator.nullsFirst(Comparator.naturalOrder()))
            .reversed();

    private final Clock clock;

    public ChallengeCrewInviteRepository(
            DynamoDomainStore store,
            ChallengeTransactionalWriter writer,
            ConditionalKeyLease leases,
            Clock clock
    ) {
        super(store, writer, leases, "CHALLENGE_CREW_INVITE", ChallengeCrewInvite.class);
        this.clock = clock;
    }

    public List<ChallengeCrewInvite> findByChallengeId(String challengeId) {
        return queryProjectionPartition(
                ChallengeRepositoryKeys.crewInvitePk(challengeId),
                ChallengeRepositoryKeys.CREATED_PREFIX,
                false
        ).stream().sorted(RECENT_FIRST).toList();
    }

    public Optional<ChallengeCrewInvite> findByChallengeIdAndInvitedUserId(
            String challengeId,
            String invitedUserId
    ) {
        if (blank(challengeId) || blank(invitedUserId)) {
            return Optional.empty();
        }
        return findUnique(ChallengeRepositoryKeys.crewInviteUniquePk(challengeId, invitedUserId));
    }

    public int deleteAllByChallengeId(String challengeId) {
        List<ChallengeCrewInvite> matches = findByChallengeId(challengeId);
        matches.forEach(this::delete);
        return matches.size();
    }

    public int deleteByChallengeIdAndInvitedUserId(String challengeId, String invitedUserId) {
        Optional<ChallengeCrewInvite> match = findByChallengeIdAndInvitedUserId(
                challengeId, invitedUserId
        );
        match.ifPresent(this::delete);
        return match.isPresent() ? 1 : 0;
    }

    public List<ChallengeCrewInvite> findByInvitedUserId(String userId) {
        if (blank(userId)) {
            return List.of();
        }
        return queryProjectionPartition(
                ChallengeRepositoryKeys.userPk(userId),
                "CREW_INVITE#",
                false
        );
    }

    public List<ChallengeCrewInvite> findByInvitedByUserId(String userId) {
        if (blank(userId)) {
            return List.of();
        }
        return queryProjectionPartition(
                ChallengeRepositoryKeys.userPk(userId),
                "CREW_INVITE_SENT#",
                false
        );
    }

    public List<ChallengeCrewInvite> findByParticipantUserId(String userId) {
        LinkedHashMap<String, ChallengeCrewInvite> distinct = new LinkedHashMap<>();
        findByInvitedUserId(userId).forEach(invite -> distinct.put(invite.getId(), invite));
        findByInvitedByUserId(userId).forEach(invite -> distinct.put(invite.getId(), invite));
        return List.copyOf(distinct.values());
    }

    @Override
    protected void prepareForSave(ChallengeCrewInvite invite, boolean creating) {
        Timestamp now = Timestamp.from(clock.instant());
        if (invite.getCreatedAt() == null) {
            invite.setCreatedAt(now);
        }
        invite.setUpdatedAt(now);
    }

    @Override
    protected String id(ChallengeCrewInvite invite) {
        return invite.getId();
    }

    @Override
    protected void assignId(ChallengeCrewInvite invite, String id) {
        invite.setId(id);
    }

    @Override
    protected String saveLockKey(ChallengeCrewInvite invite) {
        return blank(invite.getChallengeId()) || blank(invite.getInvitedUserId())
                ? invite.getId()
                : ChallengeRepositoryKeys.join(
                        "CHALLENGE", invite.getChallengeId(), "USER", invite.getInvitedUserId()
                );
    }

    @Override
    protected Collection<DynamoProjection> uniqueProjections(ChallengeCrewInvite invite) {
        if (blank(invite.getChallengeId()) || blank(invite.getInvitedUserId())) {
            return List.of();
        }
        return List.of(DynamoProjection.of(
                ChallengeRepositoryKeys.crewInviteUniquePk(
                        invite.getChallengeId(), invite.getInvitedUserId()
                ),
                ChallengeRepositoryKeys.uniqueSk()
        ));
    }

    @Override
    protected Collection<DynamoProjection> projections(ChallengeCrewInvite invite) {
        String id = ChallengeRepositoryKeys.require(invite.getId(), "crewInvite.id");
        String challengeId = ChallengeRepositoryKeys.require(
                invite.getChallengeId(), "crewInvite.challengeId"
        );
        String userId = ChallengeRepositoryKeys.require(
                invite.getInvitedUserId(), "crewInvite.invitedUserId"
        );
        String createdSk = ChallengeRepositoryKeys.createdSk(invite.getCreatedAt(), id);
        List<DynamoProjection> projections = new ArrayList<>();
        projections.add(DynamoProjection.of(
                ChallengeRepositoryKeys.crewInvitePk(challengeId), createdSk
        ));
        projections.add(DynamoProjection.of(
                ChallengeRepositoryKeys.userPk(userId),
                ChallengeRepositoryKeys.join("CREW_INVITE", ChallengeRepositoryKeys.epoch(
                        invite.getCreatedAt()
                ), id)
        ));
        if (!blank(invite.getInvitedByUserId())) {
            projections.add(DynamoProjection.of(
                    ChallengeRepositoryKeys.userPk(invite.getInvitedByUserId()),
                    ChallengeRepositoryKeys.join("CREW_INVITE_SENT", ChallengeRepositoryKeys.epoch(
                            invite.getCreatedAt()
                    ), id)
            ));
        }
        return projections;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
