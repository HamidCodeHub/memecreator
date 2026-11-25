package com.memecreator.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.memecreator.harvest.TemplateRanks;
import com.memecreator.template.LayoutRepository;
import com.memecreator.template.MemeTemplate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The "pick the templates" caption request must stay small: Groq's free tier counts prompt + max_tokens (1536)
 * against 8,000 tokens per minute, and listing every template once came to ~9,500. Estimated as chars / 4.
 */
class CaptionPromptBudgetTest {

    static final int BUDGET = 5_000;

    @TempDir
    Path tmp;

    private final LayoutRepository layouts = TestTemplates.layouts();
    /** More than the real pool today: every layout plus 300 harvested templates without one. */
    private final List<MemeTemplate> pool = TestTemplates.pool(300);

    /** Stands in for the gateway: keeps the prompt and answers with no memes. */
    static final class CapturingClient extends AiGatewayClient {
        String system;
        String user;

        CapturingClient() {
            super(null, TestTemplates.gatewayProps());
        }

        @Override
        public String chat(String systemPrompt, String userPrompt) {
            system = systemPrompt;
            user = userPrompt;
            return "{\"memes\":[]}";
        }

        int estimatedTokens() {
            return estimateInputTokens(system, user);
        }
    }

    private CapturingClient captureChoosePrompt(PromptProperties props, String topic, int count, String language,
                                                boolean faceCharacters) {
        CapturingClient client = new CapturingClient();
        TemplateShortlist shortlist = new TemplateShortlist(layouts, new TemplateRanks(tmp, new ObjectMapper()), props);
        new CaptionGenerator(client, new ObjectMapper(), layouts, shortlist, props)
                .chooseAndCaption(topic, pool, count, language, faceCharacters);
        return client;
    }

    @Test
    void aTypicalWebRequestStaysUnderBudget() {
        for (int i = 0; i < 20; i++) {   // the shortlist is random: try several
            CapturingClient c = captureChoosePrompt(new PromptProperties(15, 3, 6, 160),
                    "Inter lost the derby against Milan again", 6, "Italian", false);
            assertThat(c.estimatedTokens()).as("estimated input tokens").isLessThanOrEqualTo(BUDGET);
        }
    }

    @Test
    void theLargestRequestStaysUnderBudget() {
        String longTopic = "When the boss schedules a meeting at 5 pm on a Friday to discuss why nobody finishes "
                + "work on time, and then the meeting runs late. ".repeat(4);
        for (int i = 0; i < 20; i++) {
            CapturingClient c = captureChoosePrompt(new PromptProperties(15, 3, 6, 160), longTopic, 12, "English",
                    true);
            assertThat(c.estimatedTokens()).as("estimated input tokens").isLessThanOrEqualTo(BUDGET);
        }
    }

    @Test
    void listingEveryTemplateWouldBeOverBudget() {
        // the old behaviour, so this test notices if the budget check stops meaning anything
        CapturingClient c = captureChoosePrompt(new PromptProperties(10_000, 3, 6, 160),
                "Inter lost the derby against Milan again", 6, "Italian", false);
        assertThat(c.estimatedTokens()).isGreaterThan(BUDGET);
    }
}
