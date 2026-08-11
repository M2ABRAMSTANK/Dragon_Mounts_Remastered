package dmr.tests;

/**
 * Plain JUnit 5 coverage for {@link dmr.DragonMounts.server.ai.navigation.DragonPathfindingRules}
 * — no gametest server needed, since every method that class hosts is a pure function
 * over primitives (see that class's javadoc for the current wave-8 scope).
 *
 * <p>
 * <b>W8-PF13a (this commit): scaffolding only.</b> {@code DragonPathfindingRules}
 * currently hosts {@code shouldAllowSwimming}, but that predicate's production call site
 * — and therefore the behavior this test class needs to assert against — is wired in by
 * W8-PF3, a later commit in this wave outside this cluster's scope (nav-foundation:
 * W8-PF13a harness + W8-PF9/W8-PF4 composite fidelity only). Per this commit's own rule
 * ("every test must first be demonstrated RED on pre-fix HEAD"), asserting on {@code
 * shouldAllowSwimming} now — before W8-PF3 has decided its final call-site wiring and
 * before there is a "pre-fix" to be red against — would itself be exactly the kind of
 * vacuous, always-green test this harness exists to prevent. Real coverage lands with
 * W8-PF3.
 */
public class PathfindingRulesTests {}
