package com.classroom.modules.classroom.service;

import com.classroom.modules.classroom.model.ClassMember;
import com.classroom.modules.classroom.repository.ClassMemberRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * D-19: the transactional half of the expiry sweep - ACTIVE members whose paid access has lapsed become EXPIRED and each emits one
 * MEMBER_EXPIRED outbox event. The scheduling half is {@link MembershipExpirySweeper}.
 *
 * <p>One batch is one READ_COMMITTED transaction: the lapsed rows are selected FOR UPDATE (a current read, so a purchase that extended a
 * row a moment ago is seen and that row simply no longer matches), flipped, and their events written in the same commit. The sweep is
 * idempotent - once flipped a row no longer matches - and safe to run from several instances at once (row locks serialise them). It never
 * touches the stored state of a person whose access is still running, so the stricter check in {@code AccessPolicy.isMember} (which already
 * treats a lapsed date as "not a member") and this sweep can never disagree about who is locked out; the sweep only makes the stored
 * state, the roster, the headcounts and the Neo4j edge catch up.</p>
 */
@Service
public class MembershipExpiryService {

    /** What one batch did: rows looked at (caps the loop) and rows actually flipped to EXPIRED. */
    public record BatchResult(int examined, int expired) {}

    private final ClassMemberRepository memberRepository;
    private final ClassMembershipService membershipService;

    public MembershipExpiryService(ClassMemberRepository memberRepository, ClassMembershipService membershipService) {
        this.memberRepository = memberRepository;
        this.membershipService = membershipService;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public BatchResult sweepBatch(int batchSize, Instant now) {
        List<ClassMember> lapsed = memberRepository.findLapsedActiveForUpdate(now, PageRequest.of(0, Math.max(1, batchSize)));
        int expired = 0;
        for (ClassMember member : lapsed) {
            if (membershipService.expire(member, now)) {
                expired++;
            }
        }
        return new BatchResult(lapsed.size(), expired);
    }
}
