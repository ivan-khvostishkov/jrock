import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The cost line of the stats block, and the running total read back out of a log.
 * <p>
 * What a request cost is logged only where a model card states a price for the model in
 * that region: Grok 4.3, GPT-5.4 and GPT-6 Astra from their cards, Voxtral Small from the
 * pricing page its card points to - and nothing at all for a model or a region with none.
 * The rate is not printed, so each case asserts the cost it produces, at token counts
 * chosen to divide exactly: a round number of dollars is the card's rate, scaled. No GUI.
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
    @DisplayName("Grok 4.3 is costed from its card: $1.25 in, $2.50 out, GovCloud higher")
    void pricesGrokFromItsCard() throws Exception {
        // 1M in at $1.25 and 200K out at $2.50 per 1M.
        assertThat(costLine("xai.grok-4.3", "us-west-2", 1_000_000, 200_000))
                .isEqualTo("\nCost (model card): in $1.25 + out $0.50 = $1.75");
        // GovCloud's own rates: $1.50 and $3.00, here a million of each.
        assertThat(costLine("xai.grok-4.3", "us-gov-west-1", 1_000_000, 1_000_000))
                .contains("in $1.50 + out $3.00 = $4.50");
    }

    @Test
    @DisplayName("GPT-6 Astra costs double past 272K input tokens, and nothing off mantle's regions")
    void pricesAstraByContextLength() throws Exception {
        // Up to 272K: $11 / $55 per 1M, so 100K of each is a tenth of that.
        assertThat(costLine("openai.gpt-6-astra", "us-east-1", 100_000, 100_000))
                .contains("in $1.10 + out $5.50 = $6.60");
        // Past it: $22 / $82.50 per 1M - a million in, 100K out.
        assertThat(costLine("openai.gpt-6-astra", "us-east-1", 1_000_000, 100_000))
                .contains("in $22.00 + out $8.25 = $30.25");
        assertThat(costLine("openai.gpt-6-astra", "eu-west-1", 1000, 1000)).isEmpty();
    }

    @Test
    @DisplayName("GPT-5.4 costs more past 272K input tokens, GovCloud only up to it")
    void pricesGpt54ByContextLength() throws Exception {
        // Up to 272K: $2.75 / $16.50 per 1M, here 200K of each.
        assertThat(costLine("openai.gpt-5.4", "us-east-2", 200_000, 200_000))
                .contains("in $0.55 + out $3.30 = $3.85");
        // Past it: $5.50 / $24.75 per 1M - a million in, 200K out.
        assertThat(costLine("openai.gpt-5.4", "us-east-2", 1_000_000, 200_000))
                .contains("in $5.50 + out $4.95 = $10.45");
        // GovCloud: $3.375 / $20.25 per 1M, here 80K of each.
        assertThat(costLine("openai.gpt-5.4", "us-gov-west-1", 80_000, 80_000))
                .contains("in $0.27 + out $1.62 = $1.89");
        // And no price there past 272K, so no line at all.
        assertThat(costLine("openai.gpt-5.4", "us-gov-west-1", 272_001, 0)).isEmpty();
        assertThat(costLine("openai.gpt-5.4", "eu-west-1", 1000, 1000)).isEmpty();
    }

    @Test
    @DisplayName("Voxtral Small is costed by region, and not where the page lists no row")
    void pricesVoxtralByRegion() throws Exception {
        // $0.10 / $0.30 per 1M in the US, and a few thousandths more in Sydney:
        // $0.103 / $0.309. A million of each, so the cost IS the rate - and under a
        // dollar it is written to a tenth of a cent, which is what tells the two apart.
        assertThat(costLine("mistral.voxtral-small-24b-2507", "us-east-2",
                1_000_000, 1_000_000)).contains("in $0.100 + out $0.300 = $0.400");
        assertThat(costLine("mistral.voxtral-small-24b-2507", "ap-southeast-2",
                1_000_000, 1_000_000)).contains("in $0.103 + out $0.309 = $0.412");
        assertThat(costLine("mistral.voxtral-small-24b-2507", "eu-central-1", 1000, 1000))
                .isEmpty();
    }

    @Test
    @DisplayName("the smaller the request, the more digits it is costed to")
    void writesSmallAmountsToMoreDigits() throws Exception {
        // Grok 4.3 at $1.25 / $2.50 per 1M. Under a dollar the total is written to a
        // tenth of a cent, and under a cent to a hundredth of one, so a real request
        // does not print as $0.00.
        assertThat(costLine("xai.grok-4.3", "us-east-1", 100_000, 100_000))
                .contains("in $0.125 + out $0.250 = $0.375");
        assertThat(costLine("xai.grok-4.3", "us-east-1", 2000, 2000))
                .contains("in $0.0025 + out $0.0050 = $0.0075");
    }

    @Test
    @DisplayName("no price, no counts, no line - and never a guess")
    void logsNothingWithoutAPrice() throws Exception {
        // A card that states no price, and a model there is no card for at all.
        assertThat(costLine("moonshotai.kimi-k2.5", "us-east-1", 1000, 1000)).isEmpty();
        assertThat(costLine("someone.unknown-model", "us-east-1", 1000, 1000)).isEmpty();
        // A known price, but the reply reported no token counts: nothing to multiply,
        // so nothing is written - not a line about the rate instead.
        assertThat(costLine("xai.grok-4.3", "us-east-1", -1, -1)).isEmpty();
        assertThat(costLine("xai.grok-4.3", "us-east-1", 1000, -1)).isEmpty();
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

    private String costLine(String model, String region, long in, long out) throws Exception {
        Field m = field("MODEL_ID"), r = field("REGION");
        if (savedModel == null) savedModel = (String) m.get(null);
        if (savedRegion == null) savedRegion = (String) r.get(null);
        m.set(null, model);
        r.set(null, region);
        Method line = JRock.class.getDeclaredMethod("costLine", long.class, long.class);
        line.setAccessible(true);
        return (String) line.invoke(null, in, out);
    }

    private static Field field(String name) throws Exception {
        Field f = JRock.class.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }
}
