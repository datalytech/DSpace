/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.content.authority.script;

import static org.dspace.content.authority.script.MatchAuthorityScript.Quality.BEST;
import static org.dspace.content.authority.script.MatchAuthorityScript.Quality.EXACT;
import static org.dspace.content.authority.script.MatchAuthorityScript.Quality.TOKENS;
import static org.dspace.content.authority.script.MatchAuthorityScript.normalize;
import static org.dspace.content.authority.script.MatchAuthorityScript.quality;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

import java.util.List;

import org.junit.Test;

/**
 * Unit tests for the name comparison of {@link MatchAuthorityScript}, the check
 * that decides whether the entity found by the authority really is the one the
 * metadata value names.
 *
 * @author Claude Code
 */
public class MatchAuthorityScriptTest {

    @Test
    public void normalizeIgnoresCaseAccentsAndPunctuation() {
        assertThat(normalize("Papadópoulos, Geórgios"), is("papadopoulos georgios"));
        assertThat(normalize("  PAPADOPOULOS,   GEORGIOS "), is("papadopoulos georgios"));
        assertThat(normalize(null), is(""));
        assertThat(normalize("   "), is(""));
    }

    @Test
    public void normalizeFoldsTheGreekFinalSigma() {
        assertThat(normalize("Παπαδόπουλος Γεώργιος"), is(normalize("ΠΑΠΑΔΟΠΟΥΛΟΣ ΓΕΩΡΓΙΟΣ")));
    }

    @Test
    public void theNameOfTheEntityIsAnExactMatch() {
        assertThat(quality("Papadopoulos, Georgios", List.of("Papadopoulos, Georgios")), is(EXACT));
        assertThat(quality("papadopoulos, georgios", List.of("Papadópoulos, Georgios")), is(EXACT));
    }

    @Test
    public void anAlternativeNameOfTheEntityIsAnExactMatch() {
        List<String> names = List.of("Papadopoulos, Georgios", "Papadopoulos, G.", "Παπαδόπουλος, Γεώργιος");
        assertThat(quality("Papadopoulos, G.", names), is(EXACT));
        assertThat(quality("Παπαδόπουλος, Γεώργιος", names), is(EXACT));
    }

    @Test
    public void theSameWordsInAnotherOrderMatchOnTheirTokens() {
        assertThat(quality("Georgios Papadopoulos", List.of("Papadopoulos, Georgios")), is(TOKENS));
    }

    @Test
    public void anotherNameIsNotVerified() {
        assertThat(quality("Papadopoulos, Georgios", List.of("Papadopoulos, Ioannis")), is(BEST));
        assertThat(quality("Papadopoulos, G.", List.of("Papadopoulos, Georgios")), is(BEST));
        assertThat(quality("Department of Informatics", List.of()), is(BEST));
    }

    @Test
    public void theNameOfAnOrgUnitIsMatchedTheSameWay() {
        List<String> names = List.of("Department of Informatics and Telecommunications", "Τμήμα Πληροφορικής");
        assertThat(quality("DEPARTMENT OF INFORMATICS AND TELECOMMUNICATIONS", names), is(EXACT));
        assertThat(quality("Τμήμα Πληροφορικής", names), is(EXACT));
        assertThat(quality("Department of Informatics", names), is(BEST));
    }

}
