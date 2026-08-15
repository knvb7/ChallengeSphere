package com.ironside.challengesphere.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.ironside.challengesphere.model.Challenge;

@Repository
public interface ChallengeRepository extends JpaRepository<Challenge, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Challenge c WHERE c.id = :challengeId")
    Optional<Challenge> findByIdForUpdate(@Param("challengeId") Long challengeId);

    @Query(value = "SELECT * FROM challengesphere.challenges WHERE creator_id = :creatorId ORDER BY created_at DESC, id DESC", nativeQuery = true)
    List<Challenge> findByCreatorId(@Param("creatorId") Long creatorId);

    @Query(value = """
            SELECT * FROM challengesphere.challenges
            WHERE creator_id = :creatorId AND private = false AND active = true
            ORDER BY created_at DESC, id DESC
            """, nativeQuery = true)
    List<Challenge> findPublicActiveByCreatorId(@Param("creatorId") Long creatorId);

    @Query(value = "SELECT * FROM challengesphere.challenges WHERE current_owner_id = :ownerId ORDER BY created_at DESC, id DESC", nativeQuery = true)
    List<Challenge> findByCurrentOwnerId(@Param("ownerId") Long ownerId);

    @Query(value = "SELECT * FROM challengesphere.challenges WHERE active = true ORDER BY created_at DESC, id DESC", nativeQuery = true)
    List<Challenge> findAllActiveChallenges();

    @Query("SELECT c FROM Challenge c WHERE c.id IN :challengeIds AND c.active = true")
    List<Challenge> findActiveByIds(@Param("challengeIds") List<Long> challengeIds);

    @Query(value = "SELECT * FROM challengesphere.challenges WHERE private = false AND active = true ORDER BY created_at DESC, id DESC", nativeQuery = true)
    List<Challenge> findAllPublicChallenges();

    @Query(value = "SELECT * FROM challengesphere.challenges WHERE private = true AND active = true ORDER BY created_at DESC, id DESC", nativeQuery = true)
    List<Challenge> findAllPrivateChallenges();

    // Recent challenges - ordered by creation date
    @Query(value = "SELECT * FROM challengesphere.challenges WHERE active = true AND private = false ORDER BY created_at DESC, id DESC LIMIT :limit", nativeQuery = true)
    List<Challenge> findRecentPublicChallenges(@Param("limit") int limit);

    // Recent challenges with pagination
    @Query(value = "SELECT * FROM challengesphere.challenges WHERE active = true AND private = false ORDER BY created_at DESC, id DESC LIMIT :limit OFFSET :offset", nativeQuery = true)
    List<Challenge> findRecentPublicChallengesPaginated(@Param("limit") int limit, @Param("offset") int offset);

    // Count public active challenges
    @Query(value = "SELECT COUNT(*) FROM challengesphere.challenges WHERE active = true AND private = false", nativeQuery = true)
    long countPublicActiveChallenges();

    @Query(value = "SELECT * FROM challengesphere.challenges WHERE start_date = :startDate", nativeQuery = true)
    List<Challenge> findByStartDate(@Param("startDate") LocalDate startDate);

    @Query(value = "SELECT * FROM challengesphere.challenges WHERE start_date >= :fromDate AND start_date <= :toDate", nativeQuery = true)
    List<Challenge> findByStartDateBetween(@Param("fromDate") LocalDate fromDate, @Param("toDate") LocalDate toDate);

    @Query(value = "SELECT * FROM challengesphere.challenges WHERE title ILIKE %:searchTerm% OR description ILIKE %:searchTerm% ORDER BY created_at DESC, id DESC",
           nativeQuery = true)
    List<Challenge> searchChallengesByTitleOrDescription(@Param("searchTerm") String searchTerm);

    @Query(value = "SELECT * FROM challengesphere.challenges WHERE hashtags ILIKE %:hashtag% ORDER BY created_at DESC, id DESC", nativeQuery = true)
    List<Challenge> findByHashtagsContaining(@Param("hashtag") String hashtag);

    @Query(value = "SELECT * FROM challengesphere.challenges WHERE categories ILIKE %:category% ORDER BY created_at DESC, id DESC", nativeQuery = true)
    List<Challenge> findByCategoriesContaining(@Param("category") String category);

    @Query(value = "SELECT * FROM challengesphere.challenges WHERE number_of_days = :numberOfDays", nativeQuery = true)
    List<Challenge> findByNumberOfDays(@Param("numberOfDays") Integer numberOfDays);

    @Modifying
    @Query(value = "UPDATE challengesphere.challenges SET active = :active WHERE id = :id", nativeQuery = true)
    void updateChallengeActiveStatus(@Param("id") Long id, @Param("active") Boolean active);

    List<Challenge> findByCreatorIdInOrCurrentOwnerIdIn(List<Long> creatorIds, List<Long> ownerIds);
}
