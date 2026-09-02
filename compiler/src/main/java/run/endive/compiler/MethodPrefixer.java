package run.endive.compiler;

import run.endive.wasm.WasmModule;

/**
 * Supplies the human readable prefix used when naming the JVM method compiled for a WASM function.
 *
 * <p>The compiler derives every method name as {@code <sanitized prefix>_<funcId>}. The prefixer
 * only controls the prefix; the compiler owns the rest of the name. That split keeps two
 * invariants that the rest of the compiler and any external tooling can rely on, regardless of
 * what a prefixer returns:
 *
 * <ul>
 *   <li>method names are unique, because the function id is unique
 *   <li>the function id can always be recovered by parsing the {@code _<funcId>} suffix
 * </ul>
 *
 * <p>Characters that are illegal in a JVM method name ({@code . ; [ / < >}, see
 * <a href="https://docs.oracle.com/javase/specs/jvms/se21/html/jvms-4.html#jvms-4.2.2">JVM Spec
 * §4.2.2</a>) are replaced with {@code _}. A prefixer that needs to preserve the original name
 * exactly can avoid the substitution by encoding those characters itself, for example by
 * percent-encoding them.
 *
 * <p>The prefix is a hint for humans reading a thread dump or a profile. Tooling should never
 * parse it; it should use the function id instead.
 */
@FunctionalInterface
public interface MethodPrefixer {

    /**
     * The prefix used when no prefixer is configured, and the fallback whenever a prefixer returns
     * {@code null}, an empty string, or a string that sanitizes to nothing.
     */
    String DEFAULT_PREFIX = "func";

    /**
     * Returns the prefix for the method compiled for {@code funcId}, or {@code null} to use
     * {@link #DEFAULT_PREFIX}.
     *
     * @param funcId the WASM function index, covering imported and defined functions
     * @param module the module being compiled
     */
    String getMethodPrefix(int funcId, WasmModule module);

    /** Returns the default prefixer, naming every method {@value #DEFAULT_PREFIX}. */
    static MethodPrefixer defaultPrefixer() {
        return (funcId, module) -> DEFAULT_PREFIX;
    }

    /**
     * Returns a prefixer that uses the function name from the module's name custom section, falling
     * back to {@link #DEFAULT_PREFIX} for functions without one.
     */
    static MethodPrefixer fromNameSection() {
        return (funcId, module) -> {
            var nameSection = module.nameSection();
            return nameSection == null ? null : nameSection.nameOfFunction(funcId);
        };
    }
}
