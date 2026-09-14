package com.potner.diary.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 종별 일기 페르소나다. 꽃말을 성격으로 옮긴 기획 자료에서 왔다.
 *
 * <p>기획 자료의 완성된 시스템 프롬프트를 담지 않는다. 그쪽은 로봇 대화 응답용이라 출력 형식과
 * 런타임 입력이 일기와 다르다. 조각만 들고 일기용 프롬프트는 {@code DiaryPromptFactory} 가 만든다.
 *
 * <p>지원 종 여섯 중 넷만 행이 있다. 없는 종은 기본 화법으로 쓴다.
 */
@Entity
@Table(name = "species_persona")
public class SpeciesPersona {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "species_id", length = 36, nullable = false, columnDefinition = "char(36)")
    private String speciesId;

    @Column(name = "character_name", length = 50, nullable = false)
    private String characterName;

    @Column(name = "flower_meaning", length = 100, nullable = false)
    private String flowerMeaning;

    /**
     * 프로필에 보여줄 성격이다.
     *
     * <p>일기 프롬프트는 쓰지 않는다. {@link #personaConcept} 과 나눠 둔 이유가 그것이다 —
     * 저쪽은 모델에게 주는 역할 지시문이라 직업 은유("섬세한 조향사")로 쓰여 있고, 사용자가
     * 식물의 성격을 물었을 때 사람 직업이 답으로 나오면 겉돈다.
     */
    @Column(name = "personality", length = 200, nullable = false)
    private String personality;

    /**
     * 일기에서 맡을 역할의 한 줄 요약이다. <strong>프롬프트 전용이다.</strong>
     *
     * <p>화면에 내보내지 않는다. 사용자에게 보일 성격은 {@link #personality} 다.
     */
    @Column(name = "persona_concept", length = 200, nullable = false)
    private String personaConcept;

    /**
     * 프로필 해시태그용 짧은 키워드다. 쉼표로 구분한다.
     *
     * <p>일기 프롬프트는 쓰지 않는다. 프롬프트에는 이미 같은 내용이 문장으로 들어가 있어
     * 키워드를 더 넣으면 같은 말을 두 번 하는 셈이다. 화면 표시 전용이다.
     */
    @Column(name = "persona_tags", length = 200, nullable = false)
    private String personaTags;

    @Column(name = "core_value", length = 200, nullable = false)
    private String coreValue;

    @Column(name = "first_person", length = 20, nullable = false)
    private String firstPerson;

    @Column(name = "address_user", length = 20, nullable = false)
    private String addressUser;

    @Column(name = "speech_style", length = 300, nullable = false)
    private String speechStyle;

    @Column(name = "narrative_role", length = 100, nullable = false)
    private String narrativeRole;

    @Column(name = "primary_focus", length = 200, nullable = false)
    private String primaryFocus;

    @Column(name = "diary_structure", length = 200, nullable = false)
    private String diaryStructure;

    @Column(name = "sentence_rhythm", length = 100, nullable = false)
    private String sentenceRhythm;

    @Column(name = "signature_words", length = 200, nullable = false)
    private String signatureWords;

    @Column(name = "ending_rule", length = 200, nullable = false)
    private String endingRule;

    @Column(name = "forbidden_style", length = 200, nullable = false)
    private String forbiddenStyle;

    @Column(name = "title_pattern", length = 100, nullable = false)
    private String titlePattern;

    @Column(name = "contrast_anchor", length = 300, nullable = false)
    private String contrastAnchor;

    @Column(name = "diary_example", length = 500, nullable = false)
    private String diaryExample;

    protected SpeciesPersona() {
    }

    public String getSpeciesId() {
        return speciesId;
    }

    public String getCharacterName() {
        return characterName;
    }

    public String getFlowerMeaning() {
        return flowerMeaning;
    }

    public String getPersonality() {
        return personality;
    }

    public String getPersonaConcept() {
        return personaConcept;
    }

    public String getPersonaTags() {
        return personaTags;
    }

    public String getCoreValue() {
        return coreValue;
    }

    public String getFirstPerson() {
        return firstPerson;
    }

    public String getAddressUser() {
        return addressUser;
    }

    public String getSpeechStyle() {
        return speechStyle;
    }

    public String getNarrativeRole() {
        return narrativeRole;
    }

    public String getPrimaryFocus() {
        return primaryFocus;
    }

    public String getDiaryStructure() {
        return diaryStructure;
    }

    public String getSentenceRhythm() {
        return sentenceRhythm;
    }

    public String getSignatureWords() {
        return signatureWords;
    }

    public String getEndingRule() {
        return endingRule;
    }

    public String getForbiddenStyle() {
        return forbiddenStyle;
    }

    public String getTitlePattern() {
        return titlePattern;
    }

    public String getContrastAnchor() {
        return contrastAnchor;
    }

    public String getDiaryExample() {
        return diaryExample;
    }
}
