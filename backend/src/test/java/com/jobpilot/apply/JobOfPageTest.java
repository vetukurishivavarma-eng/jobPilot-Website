package com.jobpilot.apply;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The extension finds the prepared application from the page URL the user has open. */
class JobOfPageTest {
    @Test
    void leverAndGreenhousePagesIncludingTheEmbeddedForm() {
        assertArrayEquals(new String[]{"lever", "6cbbe304-e065-4711-bf3e-756795d2bc2a"},
                ApplicationService.jobOfPage("https://jobs.lever.co/hevodata/6cbbe304-e065-4711-bf3e-756795d2bc2a/apply"));
        assertArrayEquals(new String[]{"lever", "6cbbe304-e065-4711-bf3e-756795d2bc2a"},
                ApplicationService.jobOfPage("https://jobs.lever.co/hevodata/6cbbe304-e065-4711-bf3e-756795d2bc2a/thanks"), "thank-you page too");
        assertArrayEquals(new String[]{"greenhouse", "8155938"},
                ApplicationService.jobOfPage("https://job-boards.greenhouse.io/hackerrank/jobs/8155938?gh_src=x"));
        assertArrayEquals(new String[]{"greenhouse", "8155938"},
                ApplicationService.jobOfPage("https://job-boards.greenhouse.io/hackerrank/jobs/8155938/confirmation"));
        assertArrayEquals(new String[]{"greenhouse", "7529446"},
                ApplicationService.jobOfPage("https://job-boards.greenhouse.io/embed/job_app?for=okta&token=7529446"), "Okta's embedded iframe");
        assertNull(ApplicationService.jobOfPage("https://www.okta.com/company/careers/opportunity/7529446?gh_jid=7529446"), "the host page isn't the form");
        assertNull(ApplicationService.jobOfPage("https://example.com/jobs/1"));
    }
}
