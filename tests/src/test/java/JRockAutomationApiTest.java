import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The automation API and the interface that lists it are the same list.
 * <p>
 * {@code JRock.Automation} exists to be read: it is the whole surface an agent may call,
 * in one declaration, and the README's cheat sheet names it so that the documentation has
 * a single place to be checked against. That only works while the two agree, and nothing
 * in the compiler makes them - the calls themselves are static methods, which an
 * interface cannot declare. So this test does: a public {@code automation*} method added
 * to {@code JRock} and left out of the interface fails here, and so does the reverse.
 * <p>
 * No window and no model: this is reflection over the class, and it runs in milliseconds.
 *
 * @see JRockGuiFixture for the tests that drive the real window
 */
class JRockAutomationApiTest {

    @Test
    @DisplayName("every public automation call is declared in JRock.Automation, and vice versa")
    void theInterfaceListsTheWholeApi() {
        Set<String> statics = signatures(Arrays.stream(JRock.class.getDeclaredMethods())
                .filter(m -> Modifier.isPublic(m.getModifiers()))
                .filter(m -> Modifier.isStatic(m.getModifiers()))
                .filter(m -> m.getName().startsWith("automation"))
                // automation() is the way to HOLD the API, not one of its calls.
                .filter(m -> !m.getName().equals("automation"))
                .collect(Collectors.toList()));
        Set<String> declared = signatures(
                Arrays.asList(JRock.Automation.class.getDeclaredMethods()));

        assertThat(statics)
                .describedAs("JRock.Automation lists every public automation call")
                .containsExactlyInAnyOrderElementsOf(declared);
        // A sanity floor: a filter that matched nothing would make the assertion above
        // pass with two empty sets.
        assertThat(statics).hasSizeGreaterThan(20);
    }

    @Test
    @DisplayName("JRock.automation() forwards to the static calls")
    void theObjectFormForwards() {
        JRock.Automation api = JRock.automation();
        assertThat(api).describedAs("JRock.automation()").isNotNull();
        // Without a window there is nothing to drive, and every call says so rather than
        // doing anything - which is the one thing that can be asserted here, and is
        // enough to prove the forwarding is wired: a method returning its own default
        // would say nothing at all.
        assertThat(api.automationWindow()).describedAs("the window, before there is one")
                .isNull();
        assertThat(api.automationPromptText())
                .describedAs("the prompt's text, before there is a window").isNull();
        assertThat(api.automationBegin("api test"))
                .describedAs("why automationBegin refuses without a window").isNotNull();
    }

    /** Each method as "name(paramType, paramType): returnType", which is what has to match. */
    private static Set<String> signatures(java.util.List<Method> methods) {
        Set<String> out = new LinkedHashSet<>();
        for (Method m : methods) {
            if (m.isSynthetic()) continue;
            out.add(m.getName() + "("
                    + Arrays.stream(m.getParameterTypes()).map(Class::getSimpleName)
                            .collect(Collectors.joining(", "))
                    + "): " + m.getReturnType().getSimpleName());
        }
        return out;
    }
}
