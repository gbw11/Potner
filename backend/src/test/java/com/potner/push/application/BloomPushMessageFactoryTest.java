package com.potner.push.application;

import com.potner.bloom.application.BloomRecordedEvent;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BloomPushMessageFactoryTest {

    private static final String BLOOM_ID = "60000000-0000-0000-0000-0000000000ff";
    private static final String PLANT_ID = "20000000-0000-0000-0000-0000000000bb";

    private final BloomPushMessageFactory messageFactory = new BloomPushMessageFactory();

    @Test
    void firstBloomAndLaterBloomsReadDifferently() {
        PushMessage first = messageFactory.create(event("로지", true));
        PushMessage later = messageFactory.create(event("로지", false));

        assertThat(first.title()).isEqualTo("첫 꽃을 피웠어요");
        assertThat(first.body()).isEqualTo("로지의 첫 꽃이 피었어요. 사진으로 남겨보세요.");
        assertThat(later.title()).isEqualTo("새 꽃이 피었어요");
        // 두 번째 꽃에도 "첫 꽃" 이라고 보내면 문구를 믿을 수 없게 된다.
        assertThat(later.body()).doesNotContain("첫 꽃");
    }

    @Test
    void bodyNeverLeaksTheFormatSpecifier() {
        for (boolean firstBloom : new boolean[]{true, false}) {
            PushMessage message = messageFactory.create(event("로지", firstBloom));

            assertThat(message.title()).isNotBlank();
            assertThat(message.body()).doesNotContain("%s").contains("로지의");
        }
    }

    @Test
    void nicknameWithFormatSpecifierIsNotInterpreted() {
        // 별명은 사용자 입력이다. 서식 문자열의 인자로만 쓰여야 한다.
        assertThat(messageFactory.create(event("100%s%d", true)).body())
                .startsWith("100%s%d의 ");
    }

    @Test
    void dataCarriesTheIdentifiersTheAppNeeds() {
        // 꽃이 폈다는 알림을 누른 사용자가 보고 싶은 것은 그날 사진이다. 날짜를 함께 보내
        // 앱이 bloomId 로 기록을 다시 조회하지 않아도 그날 포토 로그를 열 수 있게 한다.
        assertThat(messageFactory.create(event("로지", true)).data()).containsOnly(
                Map.entry("type", "BLOOM"),
                Map.entry("bloomId", BLOOM_ID),
                Map.entry("plantId", PLANT_ID),
                Map.entry("bloomDate", "2026-08-10"),
                Map.entry("route", "/growth/photos")
        );
    }

    @Test
    void bloomDateIsAPlainIsoDate() {
        // 앱이 그대로 파싱한다. 시각이나 오프셋이 붙으면 날짜 비교가 어긋난다.
        assertThat(messageFactory.create(event("로지", true)).data().get("bloomDate"))
                .isEqualTo("2026-08-10");
    }

    private BloomRecordedEvent event(String nickname, boolean firstBloom) {
        return new BloomRecordedEvent(
                BLOOM_ID,
                "10000000-0000-0000-0000-0000000000aa",
                PLANT_ID,
                nickname,
                java.time.LocalDate.parse("2026-08-10"),
                firstBloom
        );
    }
}
