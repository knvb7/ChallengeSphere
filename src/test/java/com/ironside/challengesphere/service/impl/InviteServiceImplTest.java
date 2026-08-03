package com.ironside.challengesphere.service.impl;

import com.ironside.challengesphere.api.response.InviteLinkResponse;
import com.ironside.challengesphere.model.Challenge;
import com.ironside.challengesphere.model.ChallengeInvite;
import com.ironside.challengesphere.model.User;
import com.ironside.challengesphere.repository.ChallengeInviteRepository;
import com.ironside.challengesphere.repository.ChallengeRepository;
import com.ironside.challengesphere.repository.UserChallengeRepository;
import com.ironside.challengesphere.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InviteServiceImplTest {
    private ChallengeInviteRepository inviteRepository;
    private ChallengeRepository challengeRepository;
    private UserRepository userRepository;
    private UserChallengeRepository userChallengeRepository;
    private InviteServiceImpl service;

    @BeforeEach
    void setUp() {
        inviteRepository = mock(ChallengeInviteRepository.class);
        challengeRepository = mock(ChallengeRepository.class);
        userRepository = mock(UserRepository.class);
        userChallengeRepository = mock(UserChallengeRepository.class);

        service = new InviteServiceImpl();
        ReflectionTestUtils.setField(service, "inviteBaseUrl", "https://challengesphere.com/invite");
        ReflectionTestUtils.setField(service, "challengeInviteRepository", inviteRepository);
        ReflectionTestUtils.setField(service, "challengeRepository", challengeRepository);
        ReflectionTestUtils.setField(service, "userRepository", userRepository);
        ReflectionTestUtils.setField(service, "userChallengeRepository", userChallengeRepository);
    }

    @Test
    void publicPreviewContainsSafeDecisionContextWithoutInventingPendingRecipients() throws Exception {
        String inviteCode = "Ab3Def45Gh67";
        ChallengeInvite invite = new ChallengeInvite();
        invite.setId(21L);
        invite.setInviteCode(inviteCode);
        invite.setChallengeId(9L);
        invite.setCreatedBy(4L);
        invite.setUseCount(2);
        invite.setMaxUses(10);
        invite.setActive(true);
        invite.setCreatedAt(Timestamp.from(Instant.now().minusSeconds(60)));
        invite.setExpiresAt(Timestamp.from(Instant.now().plusSeconds(86_400)));

        Challenge challenge = new Challenge();
        challenge.setId(9L);
        challenge.setTitle("Morning walking crew");
        challenge.setDescription("Walk before work.\n\nRules & Guidelines:\nPost one walk each day.\n\nBenefits:\nStart together.");
        challenge.setBenefits("Start together.");
        challenge.setCategories("Fitness,Friends");
        challenge.setChallengeMode(Challenge.MODE_DAILY);
        challenge.setProofMode(Challenge.PROOF_MODE_PHOTO_REQUIRED);
        challenge.setStartDate(LocalDate.of(2026, 8, 1));
        challenge.setNumberOfDays(14);
        challenge.setActive(true);
        challenge.setIsPrivate(true);

        User inviter = new User();
        inviter.setId(4L);
        inviter.setName("Maya Rao");
        inviter.setUsername("maya");

        when(inviteRepository.findByInviteCode(inviteCode)).thenReturn(Optional.of(invite));
        when(challengeRepository.findById(9L)).thenReturn(Optional.of(challenge));
        when(userRepository.findById(4L)).thenReturn(Optional.of(inviter));
        when(userChallengeRepository.countActiveByChallengeId(9L)).thenReturn(4);

        InviteLinkResponse response = service.getInviteDetails(inviteCode);

        assertEquals("Maya Rao", response.getInviterName());
        assertEquals("maya", response.getInviterUsername());
        assertEquals("Walk before work.", response.getChallengeDescription());
        assertEquals("Post one walk each day.", response.getChallengeRules());
        assertEquals("Start together.", response.getChallengeBenefits());
        assertEquals(LocalDate.of(2026, 8, 1), response.getChallengeStartDate());
        assertEquals(14, response.getChallengeDurationDays());
        assertEquals(Challenge.PROOF_MODE_PHOTO_REQUIRED, response.getProofMode());
        assertEquals(4, response.getParticipantCount());
        assertEquals(2, response.getAcceptedCount());
        assertEquals(8, response.getRemainingUses());
        assertNull(response.getPendingCount());
        assertTrue(response.getPrivateChallenge());
    }
}
