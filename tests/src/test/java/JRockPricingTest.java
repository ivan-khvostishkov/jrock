import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The price lines of the stats block, and the running total read back out of a log.
 * <p>
 * A price is logged only where a model card states one: Grok 4.3, GPT-5.4 and GPT-6 Astra
 * from their cards, Voxtral Small from the pricing page its card points to - and nothing at
 * all for a model or a region with none. No GUI.
 */
class JRockPricingTest {

    private String savedModel;
    private String savedRegion;

    @AfterEach
    void restore() throws Exception {
        if (savedModel != null) field("MODEL_ID").set(null, savedModel);
        if (savedRegion != null) field("REGION").set(null, savedRegion);
    }

    @Test
    @DisplayName("Grok 4.3 is priced from its card: $1.25 in, $2.50 out, GovCloud higher")
    void pricesGrokFromItsCard() throws Exception {
        String lines = priceLines("xai.grok-4.3", "us-west-2", 1_000_000, 200_000);
        assertThat(lines).isEqualTo(
                "\nPrice (model card, us-west-2): $1.25 per 1M input tokens, $2.5 per 1M output"
                + "\nCost (model card): in $1.25 + out $0.50 = $1.75");
        assertThat(priceLines("xai.grok-4.3", "us-gov-west-1", 1_000_000, 0))
                .contains("$1.5 per 1M input tokens, $3 per 1M output");
    }

    @Test
    @DisplayName("GPT-6 Astra costs double past 272K input tokens, and has no price off mantle's regions")
    void pricesAstraByContextLength() throws Exception {
        assertThat(priceLines("openai.gpt-6-astra", "us-east-1", 272_000, 0))
                .contains("$11 per 1M input tokens, $55 per 1M output");
        assertThat(priceLines("openai.gpt-6-astra", "us-east-1", 272_001, 0))
                .contains("$22 per 1M input tokens, $82.5 per 1M output");
        assertThat(priceLines("openai.gpt-6-astra", "eu-west-1", 1000, 1000)).isEmpty();
    }

    @Test
    @DisplayName("GPT-5.4 costs more past 272K input tokens, GovCloud only up to it")
    void pricesGpt54ByContextLength() throws Exception {
        assertThat(priceLines("openai.gpt-5.4", "us-east-2", 272_000, 0))
                .contains("$2.75 per 1M input tokens, $16.5 per 1M output");
        assertThat(priceLines("openai.gpt-5.4", "us-east-2", 272_001, 0))
                .contains("$5.5 per 1M input tokens, $24.75 per 1M output");
        assertThat(priceLines("openai.gpt-5.4", "us-gov-west-1", 1000, 1000))
                .contains("$3.375 per 1M input tokens, $20.25 per 1M output");
        assertThat(priceLines("openai.gpt-5.4", "us-gov-west-1", 272_001, 0)).isEmpty();
        assertThat(priceLines("openai.gpt-5.4", "eu-west-1", 1000, 1000)).isEmpty();
    }

    @Test
    @DisplayName("Voxtral Small is priced by region, and not where the page lists no row")
    void pricesVoxtralByRegion() throws Exception {
        assertThat(priceLines("mistral.voxtral-small-24b-2507", "us-east-2", 1000, 1000))
                .contains("$0.1 per 1M input tokens, $0.3 per 1M output");
        assertThat(priceLines("mistral.voxtral-small-24b-2507", "ap-southeast-2", 1000, 1000))
                .contains("$0.103 per 1M input tokens, $0.309 per 1M output");
        assertThat(priceLines("mistral.voxtral-small-24b-2507", "eu-central-1", 1000, 1000))
                .isEmpty();
    }

    @Test
    @DisplayName("a model whose card states no price gets no price line, not a guess")
    void logsNothingWithoutAPrice() throws Exception {
        assertThat(priceLines("moonshotai.kimi-k2.5", "us-east-1", 1000, 1000)).isEmpty();
        assertThat(priceLines("someone.unknown-model", "us-east-1", 1000, 1000)).isEmpty();
        // Known price, no counts from the API: the price, and no cost.
        assertThat(priceLines("xai.grok-4.3", "us-east-1", -1, -1))
                .contains("Price (model card").doesNotContain("Cost (model card)");
    }

    @Test
    @DisplayName("the startup total adds up every cost line in the log, and nothing else")
    void addsUpTheCostLinesInALog() {
        String log = String.join("\n",
                "Ready.",
                "Cost (model card): in $1.25 + out $0.50 = $1.75",
                "[OPERATOR'S ASSISTANT]",
                "@20261008-101010-000",
                "Some line that says = $100 but is no cost line",
                "Cost (model card): in $0.0010 + out $0.0020 = $0.0030",
                "");
        double[] costs = JRock.loggedCosts(log);
        assertThat(costs[0]).isCloseTo(1.753, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(costs[1]).isEqualTo(2.0);
        assertThat(JRock.loggedCosts("")).containsExactly(0.0, 0.0);
        assertThat(JRock.loggedCosts(null)).containsExactly(0.0, 0.0);
    }

    private String priceLines(String model, String region, long in, long out) throws Exception {
        Field m = field("MODEL_ID"), r = field("REGION");
        if (savedModel == null) savedModel = (String) m.get(null);
        if (savedRegion == null) savedRegion = (String) r.get(null);
        m.set(null, model);
        r.set(null, region);
        Method lines = JRock.class.getDeclaredMethod("priceLines", long.class, long.class);
        lines.setAccessible(true);
        return (String) lines.invoke(null, in, out);
    }

    private static Field field(String name) throws Exception {
        Field f = JRock.class.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }
}
