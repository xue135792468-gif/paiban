package com.paiban.scheduling.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paiban.scheduling.domain.ConstraintType;
import com.paiban.scheduling.service.ConstraintProposalNormalizer.ClassRef;
import com.paiban.scheduling.service.ConstraintProposalNormalizer.Context;
import com.paiban.scheduling.service.ConstraintProposalNormalizer.CourseRef;
import com.paiban.scheduling.service.ConstraintProposalNormalizer.TeacherRef;
import com.paiban.scheduling.web.ConstraintProposalDtos.ConstraintProposalDto;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Ticket 14/08: the envelope is lenient, the content is not. */
class ConstraintProposalParserTest {

    private final ConstraintProposalParser parser =
            new ConstraintProposalParser(new ObjectMapper());
    private final ConstraintProposalPrompt prompt = new ConstraintProposalPrompt();

    @Test
    void readsThePlainEnvelope() {
        List<JsonNode> rows = parser.parse("""
                {"proposals":[{"type":"COURSE_UNAVAILABLE"},{"type":"COURSE_SPREAD"}]}""");
        assertThat(rows).hasSize(2);
    }

    @Test
    void stripsMarkdownFencesAndSurroundingProse() {
        List<JsonNode> rows = parser.parse("""
                这是结果：
                ```json
                {"proposals":[{"type":"COURSE_SPREAD"}]}
                ```
                以上。""");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).path("type").asText()).isEqualTo("COURSE_SPREAD");
    }

    @Test
    void acceptsABareArray() {
        assertThat(parser.parse("[{\"type\":\"COURSE_SPREAD\"}]")).hasSize(1);
    }

    @Test
    void acceptsOneObjectPerLine() {
        List<JsonNode> rows = parser.parse("""
                {"type":"COURSE_SPREAD"}
                {"type":"COURSE_UNAVAILABLE"}""");
        assertThat(rows).hasSize(2);
    }

    @Test
    void refusesAnAnswerWithoutAProposalList() {
        assertThatThrownBy(() -> parser.parse("{\"answer\":\"nope\"}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("proposals");
        assertThatThrownBy(() -> parser.parse("完全不是 JSON"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------- catalogue, ticket 04

    /**
     * The 25 natural-language types keep their catalogue entry; the manual-only
     * weekly no-class type must not be offered to the model at all, because its
     * per-class slot rows cannot be derived from one sentence.
     */
    @Test
    void theCatalogueHidesTheManualOnlyTypeAndRetiredTypes() {
        String catalogue = prompt.system();

        assertThat(catalogue).doesNotContain("CLASS_UNAVAILABLE");
        assertThat(catalogue).contains("请手动设置每周不上课时段");

        List<String> listed = Arrays.stream(ConstraintType.values())
                .filter(type -> catalogue.contains("- " + type.name() + "（"))
                .map(Enum::name)
                .toList();
        assertThat(listed).containsExactlyInAnyOrderElementsOf(List.of(
                "TEACHER_CONFLICT", "CLASS_CONFLICT",
                "TEACHER_UNAVAILABLE", "COURSE_UNAVAILABLE", "SAME_COURSE_ONCE_PER_DAY",
                "TEACHER_MAX_PER_DAY", "CLASS_MAX_PER_DAY", "COURSE_MAX_PER_DAY",
                "CONSECUTIVE_LESSONS", "COURSE_SPREAD",
                "MAIN_SUBJECT_MORNING", "TEACHER_PREFERRED_SLOT", "TEACHER_MAX_CONSECUTIVE",
                "TEACHER_NO_FIRST_PERIOD", "TEACHER_DAY_OFF", "CLASS_GAP_MINIMIZED",
                "PE_LAST_PERIOD_AVOID"));
    }

    /** Even a self-confident READY never turns the manual-only type into entries. */
    @Test
    void aManualOnlyTypeStaysUnsupportedEvenWhenTheModelSaysReady() {
        ConstraintProposalDto proposal = normalizeOne("""
                {"type":"CLASS_UNAVAILABLE","status":"READY","className":"高一(1)班",
                 "days":[1],"periods":[5]}""");

        assertThat(proposal.status()).isEqualTo(ConstraintProposalNormalizer.UNSUPPORTED);
        assertThat(proposal.reason()).contains("请手动设置每周不上课时段");
        assertThat(proposal.entries()).isEmpty();
    }

    /**
     * A class-scoped sentence must not be recorded as a school-wide teacher/course
     * ban: those types have no {@code classId} scope, so the only honest answer is
     * UNSUPPORTED with empty entries.
     */
    @Test
    void aClassScopedRequestIsNeverWidenedToTheWholeSchool() {
        List<ConstraintProposalDto> proposals =
                ConstraintProposalNormalizer.normalize(parser.parse("""
                        {"proposals":[
                          {"type":"TEACHER_UNAVAILABLE","status":"READY","className":"高一(1)班",
                           "days":[3],"periods":[5,6]},
                          {"type":"COURSE_UNAVAILABLE","status":"READY","className":"高一(1)班",
                           "days":[3],"periods":[5]}]}"""), context(), "nl-1");

        assertThat(proposals).hasSize(2).allSatisfy(proposal -> {
            assertThat(proposal.status()).isEqualTo(ConstraintProposalNormalizer.UNSUPPORTED);
            assertThat(proposal.reason()).contains("行政班");
            assertThat(proposal.entries()).isEmpty();
        });
    }

    /** The threshold that really does own a classId scope keeps working. */
    @Test
    void classScopedThresholdsStillAcceptTheAdminClass() {
        List<ConstraintProposalDto> proposals =
                ConstraintProposalNormalizer.normalize(parser.parse("""
                        {"proposals":[
                          {"type":"CLASS_MAX_PER_DAY","status":"READY","className":"高一(1)班",
                           "values":{"maxPerDay":6}}]}"""), context(), "nl-1");

        assertThat(proposals).hasSize(1).allSatisfy(proposal -> {
            assertThat(proposal.status()).isEqualTo(ConstraintProposalNormalizer.READY);
            assertThat(proposal.entries()).hasSize(1);
        });
        assertThat(proposals.get(0).entries().get(0).params())
                .containsEntry("classId", 31L).containsEntry("maxPerDay", 6);
    }

    // ------------------------------------------------- fixture

    private ConstraintProposalDto normalizeOne(String proposal) {
        return ConstraintProposalNormalizer
                .normalize(parser.parse("{\"proposals\":[" + proposal + "]}"), context(), "nl-1")
                .get(0);
    }

    private static Context context() {
        List<Integer> day = List.of(1, 2, 3, 4, 5, 6, 7, 8, 9);
        return new Context(
                List.of(new TeacherRef(11, "张伟", "数学"), new TeacherRef(12, "李娜", "语文")),
                List.of(new CourseRef(21, "数学", "数学"),
                        new CourseRef(22, "化学", "化学")),
                List.of(new ClassRef(31, "高一(1)班"), new ClassRef(32, "高一(2)班")),
                Map.of(1, day, 3, day));
    }
}
