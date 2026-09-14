package com.potner.push.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface FcmTokenRepository extends JpaRepository<FcmToken, String> {

    /**
     * 설치 ID를 키로 등록 정보를 넣거나 갱신한다.
     *
     * <p>같은 기기를 다른 사용자가 등록하면 {@code user_id}까지 덮어쓴다. 이전 사용자의 등록이
     * 남아 있으면 그 사용자의 알림이 지금 로그인한 사람의 기기로 가기 때문이다.
     *
     * <p>조회 후 수정하는 방식을 쓰지 않는다. {@code user_id}를 바꾸는 구간에 경쟁이 생기고,
     * 그 창에서 두 요청이 겹치면 어느 쪽이 남는지 정해지지 않는다. 한 문장으로 처리한다.
     *
     * <p>비활성으로 내려간 토큰도 같은 기기가 다시 등록하면 {@code active}가 1로 돌아온다.
     *
     * <p>반환값을 두지 않은 이유가 있다. {@code ON DUPLICATE KEY UPDATE}의 영향 행 수는
     * 삽입 1, 갱신 2, 값이 그대로면 0이라 삽입과 갱신을 구분하는 데 쓸 수 없다.
     * {@code sensor_reading}의 중복 판정처럼 영향 행 수로 분기하면 잘못된 결론이 나온다.
     */
    @Modifying
    @Query(value = """
            INSERT INTO fcm_token (
                installation_id,
                user_id,
                token,
                platform,
                active,
                last_seen_at
            ) VALUES (
                :installationId,
                :userId,
                :token,
                :platform,
                1,
                :now
            ) AS incoming
            ON DUPLICATE KEY UPDATE
                user_id      = incoming.user_id,
                token        = incoming.token,
                platform     = incoming.platform,
                active       = 1,
                last_seen_at = incoming.last_seen_at
            """, nativeQuery = true)
    void upsert(
            @Param("installationId") String installationId,
            @Param("userId") String userId,
            @Param("token") String token,
            @Param("platform") String platform,
            @Param("now") LocalDateTime now
    );

    /**
     * 소유자가 일치할 때만 지운다.
     *
     * <p>설치 ID만으로 지우면 남의 설치 ID를 알아낸 사람이 그 기기의 푸시를 끊을 수 있다.
     */
    @Modifying
    @Query(value = """
            DELETE FROM fcm_token
             WHERE installation_id = :installationId
               AND user_id = :userId
            """, nativeQuery = true)
    int deleteOwned(
            @Param("userId") String userId,
            @Param("installationId") String installationId
    );

    /**
     * FCM 이 영구 무효라고 답한 기기를 비활성으로 내린다.
     *
     * <p>행을 지우지 않는다. 같은 기기가 새 토큰으로 다시 등록하면 {@link #upsert}가
     * {@code active}를 1 로 되돌리므로 지울 이유가 없고, 남겨 두면 기기 이력이 유지된다.
     */
    @Modifying
    @Query(value = """
            UPDATE fcm_token
               SET active = 0
             WHERE installation_id IN (:installationIds)
            """, nativeQuery = true)
    int deactivateAll(@Param("installationIds") Collection<String> installationIds);

    /** 발송 대상 조회다. 무효로 판정되어 비활성된 토큰은 제외한다. */
    List<FcmToken> findAllByUserIdAndActiveIsTrue(String userId);
}
