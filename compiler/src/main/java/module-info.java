// Suppress "module not found" warnings (and unfortunately all other module warnings) for forward
// references of qualified exports (`exports ... to ...`), see https://stackoverflow.com/q/53670052
@SuppressWarnings("module")
module run.endive.compiler {
    requires transitive run.endive.runtime;
    requires transitive run.endive.wasm;
    requires org.objectweb.asm;
    requires org.objectweb.asm.commons;
    requires org.objectweb.asm.util;

    exports run.endive.compiler;
    exports run.endive.experimental.aot;
    exports run.endive.compiler.internal to
            run.endive.build.time.compiler;
}
