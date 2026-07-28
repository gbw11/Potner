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

    @Column(name = "persona_concept", length = 200, nullable = false)
    private String personaConcept;

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

    public String getPersonaConcept() {
        return personaConcept;
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
